package com.example.migration.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;

/** 读取 docs/golden/demo.settle_order.golden.json,供比对测试和过期检测测试复用。 */
public final class GoldenDatasetLoader {

    public static final Path GOLDEN_FILE = Path.of("docs/golden/demo.settle_order.golden.json");

    private GoldenDatasetLoader() {
    }

    public static GoldenDatasetFile load() {
        if (!GOLDEN_FILE.toFile().exists()) {
            throw new IllegalStateException(
                    "找不到黄金数据集文件 " + GOLDEN_FILE.toAbsolutePath()
                            + ",需要先跑 `mvn verify -P golden-generate` 生成(见 README 黄金测试集章节)");
        }
        try {
            return new ObjectMapper().readValue(GOLDEN_FILE.toFile(), GoldenDatasetFile.class);
        } catch (IOException e) {
            throw new IllegalStateException("解析黄金数据集文件失败: " + GOLDEN_FILE, e);
        }
    }
}
