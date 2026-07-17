package icu.sakuracianna.mianba.interview.material;

/** API 进程访问独立材料解析故障域的最小端口。 */
public interface MaterialParserClient {
    /**
     * 以短超时探测材料解析进程是否可接收请求。
     *
     * @return 仅当解析进程返回严格健康响应时为 {@code true}
     */
    boolean isReady();

    /**
     * 将有限原始文件交给内部解析服务，返回已经过协议校验的有限纯文本。
     *
     * @param filename 已去除路径的上传文件名
     * @param contentType 客户端声明的 MIME 类型
     * @param payload 最大 5 MiB 的原始文件
     * @return 有限解析结果
     */
    ParsedMaterial parse(String filename, String contentType, byte[] payload);
}
