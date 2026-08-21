# 迁移前存储过程原始 DDL 备份

每个存储过程迁移前,先用以下查询导出完整可重建 DDL,存成 `{schema}.{proc_name}.sql`,作为只读留痕(不在这里改动,不会被应用启动时执行)。

```sql
SELECT pg_get_functiondef(p.oid)
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = '<schema>' AND p.proname = '<proc_name>';
```
