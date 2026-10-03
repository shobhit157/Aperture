| Bug | Check | Result | Detail |
|---|---|---|---|
| 3 | Both sides report TRANSFER_PATH | NOT REPRODUCED | sender path: None, receiver path: EVENT:TRANSFER_PATH:n1:direct |
| 1 | Sender reports success after receiver failed | NOT REPRODUCED | receiver failed, sender reported: None |
| 2 | Receiver reports a path after failing | NOT REPRODUCED | no TRANSFER_PATH after TRANSFER_FAILED |
| 4 | Sender failure never becomes TRANSFER_FAILED | NOT REPRODUCED | EVENT:TRANSFER_FAILED:g1:sender: connect timed out after 30s |
