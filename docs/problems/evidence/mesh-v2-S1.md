| Bug | Check | Result | Detail |
|---|---|---|---|
| 3 | Both sides report TRANSFER_PATH | NOT REPRODUCED | sender path: None, receiver path: EVENT:TRANSFER_PATH:n1:direct |
| 1 | Sender reports success after receiver failed | NOT REPRODUCED | receiver failed, sender reported: EVENT:FILE_SEND_FAILED:t1:file changed while sending (sent 10485760 of 524288000 bytes) |
| 2 | Receiver reports a path after failing | NOT REPRODUCED | no TRANSFER_PATH after TRANSFER_FAILED |
| 7 | No integrity check on received files | NOT REPRODUCED | receiver: hash mismatch (sender d6384980d745…, received 0c514ccd73fb…) / sender: EVENT:FILE_SEND_FAILED:c1:hash mismatch  / no file kept |
