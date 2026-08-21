package com.example.migration.golden;

import java.util.List;

/** 黄金数据集文件的顶层结构:生成时间 + openGauss 版本(排查"是不是换了个库版本导致行为不同")+ 场景列表。 */
public record GoldenDatasetFile(String generatedAt, String openGaussVersion, List<GoldenScenario> scenarios) {
}
