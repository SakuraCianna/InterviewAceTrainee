/**
 * 固定产品身份配置。
 *
 * 管理员入口和备案展示随前端版本审计发布，不属于服务器容量或密钥配置，禁止通过未受控的构建环境覆盖。
 */
export const productConfig = Object.freeze({
  adminEntryPath: "/sakuracianna",
  filing: Object.freeze({
    icpNumber: "湘ICP备2025151258号-1",
    policeRecordNumber: "",
    icpUrl: "https://beian.miit.gov.cn/",
    policeUrl: "https://beian.mps.gov.cn/#/query/webSearch",
  }),
});
