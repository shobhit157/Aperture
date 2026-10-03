| Bug | Check | Result | Detail |
|---|---|---|---|
| 3 | Both sides report TRANSFER_PATH | NOT REPRODUCED | sender path: None, receiver path: EVENT:TRANSFER_PATH:n1:direct |
| 1 | Sender reports success after receiver failed | NOT REPRODUCED | receiver failed, sender reported: EVENT:FILE_SEND_FAILED:t1:file changed while sending (sent 10485760 of 524288000 bytes) |
| 2 | Receiver reports a path after failing | NOT REPRODUCED | no TRANSFER_PATH after TRANSFER_FAILED |
| 7 | No integrity check on received files | NOT REPRODUCED | receiver: hash mismatch (sender 366bf34b79d8…, received dc9907e29f17…) / sender: EVENT:FILE_SEND_FAILED:c1:hash mismatch  / no file kept |
| 11 | Progress lines per transfer | NOT REPRODUCED | 500 MB -> sender 102, receiver 102 lines (~418 per GB, both sides) |
