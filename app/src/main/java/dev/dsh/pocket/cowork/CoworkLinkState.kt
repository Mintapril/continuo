package dev.dsh.pocket.cowork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Connection feedback is independent of whether a task exists. */
enum class CoworkLinkState(val label:String,val hint:String,val buttonLabel:String,val connecting:Boolean=false) {
    DISCONNECTED("未连接执行器","先连接本机执行器；连接不会自动创建任务或副屏。","启动并连接执行器"),
    STARTING("正在启动执行器…","如出现 KernelSU 授权提示，请在手机上确认。","正在启动…",true),
    AUTHENTICATING("正在取得控制凭证…","正在通过本机授权通道建立连接，不会向模型提供凭证。","正在认证…",true),
    CONNECTING("正在连接执行器…","正在核验本机执行器身份，请稍候。","正在连接…",true),
    CONNECTED("已连接执行器","连接正常。暂无任务时不会出现副屏；可向下开启任务岛。","执行器已连接"),
    LOST("执行器连接中断","后台操作会自动暂停；正在尝试恢复状态，也可点击重新连接。","重新连接执行器"),
    FAILED("执行器连接失败","请查看下方错误提示，检查 KernelSU 授权后重试。","重试连接"),
    ;
    val canConnect:Boolean get() = !connecting && this != CONNECTED
}

/** One bounded attempt; cancellation must never turn into a success indication. */
internal suspend fun <T> connectWithFeedback(
    report:(CoworkLinkState)->Unit,
    invalidate:()->Unit,
    attempt:suspend ()->T,
):T {
    report(CoworkLinkState.STARTING)
    try {
        val result=withTimeout(45_000){attempt()}
        report(CoworkLinkState.CONNECTED)
        return result
    } catch(e:TimeoutCancellationException) {
        invalidate();report(CoworkLinkState.FAILED)
        throw IllegalStateException("连接超时，请检查 Continuo 的 KernelSU 授权后重试",e)
    } catch(e:CancellationException) {
        invalidate();report(CoworkLinkState.DISCONNECTED);throw e
    } catch(e:Exception) {
        invalidate();report(CoworkLinkState.FAILED);throw e
    }
}
