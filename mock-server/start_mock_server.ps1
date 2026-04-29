param(
    [string]$Host = "0.0.0.0",
    [int]$Port = 18080
)

Set-Location $PSScriptRoot
python -m uvicorn mock_server.app:app --host $Host --port $Port
