# FlashDeal Code RAG Snapshot

该目录冻结 `context-lifecycle-v1` Legacy Baseline 使用的 FlashDeal Code RAG 数据，而不是重新执行导入或 embedding。

快照包含一个 PostgreSQL custom-format dump，范围严格限定为：

- `code_repository`：1 row
- `code_file`：167 rows
- `code_chunk`：642 rows，包含 642 个 `vector(1024)` embeddings

数据库中同时存在的 RuoYi-Cloud repo 未包含在该 dump 中。FlashDeal 外部源码 checkout 的 HEAD 和 dirty 状态记录在 manifest；dirty 文件未打包，避免固化本地配置或潜在密钥。A/B 的 Code RAG 数据身份以已恢复验证的数据库行和 embedding digest 为准。

## 校验

```powershell
Get-ChildItem . -File |
  Where-Object Name -NotIn @('SHA256SUMS','README.md','snapshot-manifest.json') |
  Get-FileHash -Algorithm SHA256
```

预期 SHA-256 见 `SHA256SUMS` 和 `snapshot-manifest.json`。

## 恢复到独立数据库

以下命令会创建新数据库，不覆盖现有 `jchatmind`：

```powershell
docker exec jchatmind-postgres createdb -U postgres jchatmind_flashdeal_baseline
docker cp .\flashdeal-code-rag.dump jchatmind-postgres:/tmp/flashdeal-code-rag.dump
docker exec jchatmind-postgres pg_restore `
  -U postgres `
  -d jchatmind_flashdeal_baseline `
  --no-owner `
  --no-privileges `
  /tmp/flashdeal-code-rag.dump
```

恢复后必须重新验证：

```text
repository rows: 1
file rows: 167
chunk rows: 642
embedding rows: 642
file manifest MD5: 7baa292e917e30d3c81384e2b5cf76ca
chunk manifest MD5: 988c5abbe64e95bb4bf1ee1daf169a7c
full file rows MD5: 0d2d675c6a30c63627fe31dedb1886fe
full chunk rows including embedding MD5: 77929902841960fe96ba19ad6fc8d4ce
embedding MD5: f0c30bad38b9a0cc9ad918968177335c
```

本次冻结过程已实际恢复到临时数据库并逐项对比，结果为 `PASS`。不要通过重新扫描源码或重新生成 embedding 来代替该 dump，否则不再是同一份 Code RAG snapshot。
