| Bug | Check | Result | Detail |
|---|---|---|---|
| 3 | Both sides report TRANSFER_PATH | CONFIRMED | sender: direct, receiver: direct -> Java turns both into TRANSFER_METRIC (hash ok: True) |
| 1 | Sender reports success after receiver failed | CONFIRMED | receiver: incomplete (10485760/524288000 bytes) / sender: FILE_SENT |
| 2 | Receiver reports a path after failing | CONFIRMED | TRANSFER_FAILED then EVENT:TRANSFER_PATH:t1:direct -> Java sends a success metric after the failure |
| 4 | Sender failure never becomes TRANSFER_FAILED | CONFIRMED | after 30s: EVENT:ERROR:g1:timed out (Client.java ignores EVENT:ERROR) |
