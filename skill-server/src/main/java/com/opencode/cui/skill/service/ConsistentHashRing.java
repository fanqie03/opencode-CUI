package com.opencode.cui.skill.service;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Generic consistent hash ring.
 *
 * <p>Maps arbitrary string keys to nodes by hashing them onto a virtual ring.
 * When a node is added, {@code virtualNodes} evenly-spread virtual positions are
 * inserted into the ring, which improves load distribution. When a node is removed
 * only the keys that were routed to that node are affected — all other mappings
 * remain stable.</p>
 *
 * <p>Thread-safe: uses a {@link ReentrantReadWriteLock} so concurrent reads never
 * block each other while structural mutations are fully serialised.</p>
 *
 * <p>Hash function: SHA-256 (256-bit), collapsed to a signed 64-bit long via XOR
 * folding, which gives excellent uniformity without any third-party dependency.
 * Virtual-node positions are derived by appending {@code "#N"} suffixes to the
 * node key before hashing.</p>
 *
 * @param <T> node value type (e.g. {@code WebSocketSession})
 */
@Slf4j
public class ConsistentHashRing<T> {

    /** Default number of virtual nodes per physical node. */
    public static final int DEFAULT_VIRTUAL_NODES = 150;

    /** Sorted map from hash position to node value. */
    private final TreeMap<Long, T> ring = new TreeMap<>();

    /** Keeps track of all virtual-node positions belonging to each physical node key. */
    private final Map<String, List<Long>> nodeHashes = new ConcurrentHashMap<>();

    private final int virtualNodes;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // ------------------------------------------------------------------ constructors

    /**
     * Creates a ring with the specified number of virtual nodes per physical node.
     *
     * @param virtualNodes number of virtual positions on the ring per physical node;
     *                     higher values improve balance at the cost of more memory
     */
    public ConsistentHashRing(int virtualNodes) {
        if (virtualNodes <= 0) {
            throw new IllegalArgumentException("virtualNodes must be positive, got: " + virtualNodes);
        }
        this.virtualNodes = virtualNodes;
    }

    /** Creates a ring with {@value #DEFAULT_VIRTUAL_NODES} virtual nodes per physical node. */
    public ConsistentHashRing() {
        this(DEFAULT_VIRTUAL_NODES);
    }

    // ------------------------------------------------------------------ public API

    /**
     * Adds a node to the ring.
     *
     * <p>Creates {@link #virtualNodes} virtual positions for {@code nodeKey} evenly
     * spread around the ring. If {@code nodeKey} is already present its previous
     * positions are removed first and replaced with the new {@code node} value.</p>
     *
     * @param nodeKey stable identifier for this node (e.g. session ID or host:port)
     * @param node    the value to return when a key is routed to this node
     */
    public void addNode(String nodeKey, T node) {
        lock.writeLock().lock();
        try {
            // Remove stale positions if the key is being updated
            removeNodePositions(nodeKey);

            List<Long> positions = new ArrayList<>(virtualNodes);
            for (int i = 0; i < virtualNodes; i++) {
                long position = hash(nodeKey + "#" + i);
                ring.put(position, node);
                positions.add(position);
            }
            nodeHashes.put(nodeKey, positions);
            log.info("[ENTRY] ConsistentHashRing.addNode nodeKey={} virtualNodes={} ringSize={}",
                    nodeKey, virtualNodes, ring.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a node and all its virtual positions from the ring.
     *
     * <p>Keys that were routed to this node will be remapped to the next surviving
     * node on the ring. All other key-to-node mappings are unaffected.</p>
     *
     * @param nodeKey the key that was used when adding the node
     */
    public void removeNode(String nodeKey) {
        lock.writeLock().lock();
        try {
            removeNodePositions(nodeKey);
            nodeHashes.remove(nodeKey);
            log.info("[EXIT] ConsistentHashRing.removeNode nodeKey={} ringSize={}",
                    nodeKey, ring.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the node responsible for the given key.
     *
     * <p>Hashes {@code key} to a position, then walks clockwise on the ring to find
     * the first virtual node at or beyond that position. Wraps around to the smallest
     * position if the hash falls beyond the last entry.</p>
     *
     * @param key routing key (e.g. session ID, user ID)
     * @return the mapped node, or {@code null} if the ring is empty
     */
    public T getNode(String key) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return null;
            }
            long position = hash(key);
            Map.Entry<Long, T> entry = ring.ceilingEntry(position);
            if (entry == null) {
                // Wrap around to the beginning of the ring
                entry = ring.firstEntry();
            }
            return entry.getValue();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the number of <em>physical</em> nodes currently on the ring.
     *
     * @return physical node count
     */
    public int size() {
        lock.readLock().lock();
        try {
            return nodeHashes.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns {@code true} if there are no nodes on the ring. */
    public boolean isEmpty() {
        return size() == 0;
    }

    // ------------------------------------------------------------------ private helpers

    /**
     * Removes all virtual positions for {@code nodeKey} from the ring (without
     * touching the {@link #nodeHashes} map — that is the caller's responsibility).
     * Must be called while holding the write lock.
     */
    private void removeNodePositions(String nodeKey) {
        List<Long> positions = nodeHashes.get(nodeKey);
        if (positions != null) {
            positions.forEach(ring::remove);
        }
    }

    /**
     * Hashes a string to a 64-bit long using SHA-256.
     *
     * <p>SHA-256 produces 256 bits (32 bytes); we XOR-fold all four 64-bit segments
     * into a single long value, retaining full entropy while giving a well-distributed
     * mapping across the ring.</p>
     *
     * @param key the string to hash
     * @return a 64-bit hash value (may be negative — the ring uses unsigned ordering
     *         via {@link Long#compareUnsigned} implicitly through {@link TreeMap})
     */
    private static long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            // Fold 256-bit digest into 64 bits via XOR of four 64-bit segments
            long h = bytesToLong(digest, 0);
            h ^= bytesToLong(digest, 8);
            h ^= bytesToLong(digest, 16);
            h ^= bytesToLong(digest, 24);
            return h;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on every JVM
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Reads 8 bytes from {@code bytes} starting at {@code offset} as a big-endian long. */
    private static long bytesToLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }
}
