package com.yuki.yukihub.metadata;

/**
 * 元数据来源相关常量定义。
 * 原有的 Delegate 接口和实例方法已随 MainActivity 一并移除，
 * 元数据获取流程改由 apps 端通过 LauncherMetadataBridge 调用各 Client 完成。
 */
public final class MetadataController {

    private MetadataController() {
    }

    public static final String SOURCE_VNDB = "vndb";
    public static final String SOURCE_BANGUMI = "bangumi";
    public static final String SOURCE_BANGUMI_MIRROR = "bangumi_mirror";
    public static final String SOURCE_YMGAL = "ymgal";

    public static final String KEY_METADATA_SOURCE = "metadata_source";
    public static final String KEY_VISIBLE_METADATA_SOURCE_PREFIX = "visible_metadata_source_";
    public static final String KEY_BANGUMI_TOKEN = "bangumi_token";
    public static final String KEY_SIDE_TRANSLATED_PREFIX = "side_translated_";
}
