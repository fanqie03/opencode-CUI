package com.opencode.cui.skill.service.sync;

import com.opencode.cui.skill.model.SyncMode;
import com.opencode.cui.skill.model.SyncRequest;

public interface MultiDeviceSyncService {

    SyncMode getSyncMode();

    void push(SyncRequest request);
}
