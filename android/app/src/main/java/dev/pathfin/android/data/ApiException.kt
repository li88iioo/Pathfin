package dev.pathfin.android.data

import java.io.IOException

/** 错误文案只包含固定说明，避免把响应、token 或带签名的 URL 暴露给界面。 */
class ApiException(val statusCode: Int?, message: String) : IOException(message)
