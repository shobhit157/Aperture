| Bug | Check | Result | Detail |
|---|---|---|---|
| 3 | Both sides report TRANSFER_PATH | NOT REPRODUCED | sender path: None, receiver path: EVENT:TRANSFER_PATH:n1:direct |
| 1 | Sender reports success after receiver failed | NOT REPRODUCED | receiver failed, sender reported: EVENT:FILE_SEND_FAILED:t1:incomplete (10485837/524288000 bytes) |
| 2 | Receiver reports a path after failing | NOT REPRODUCED | no TRANSFER_PATH after TRANSFER_FAILED |
| 4 | Sender failure never becomes TRANSFER_FAILED | NOT REPRODUCED | EVENT:TRANSFER_FAILED:g1:sender: timed out |
| 5 | Parallel same filename corrupts file | NOT REPRODUCED | 0/3 runs bad; last: run 3: ok, two files: received_p3a_same.bin, received_p3b_same.bin |
| 7 | No integrity check on received files | NOT REPRODUCED | receiver: hash mismatch (sender d5a5b144ded5…, received e017e9b31f82…) / sender: EVENT:FILE_SEND_FAILED:c1:hash mismatch  / no file kept |
| 8 | '|' in filename breaks header | NOT REPRODUCED | EVENT:FILE_RECEIVED:w1:received_w1_we_ird.txt¦6 |
| S | Speed 100 MB | 52.3 MB/s | total 1.9s (setup 0.0s, data 53.1 MB/s), final path direct, path changes -, progress lines sender 1600 / receiver 1601 |
| S | Speed 500 MB | 42.9 MB/s | total 11.7s (setup 0.0s, data 42.9 MB/s), final path direct, path changes -, progress lines sender 8000 / receiver 8029 |
