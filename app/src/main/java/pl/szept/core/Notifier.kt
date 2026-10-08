package pl.szept.core

/** Powiadomienia systemowe (implementacja androidowa w pakiecie service). */
interface Notifier {
    fun onMessage(chatId: String, chatName: String, sender: String, text: String, isGroup: Boolean)
    fun cancel(chatId: String)
    fun onDeleted(chatId: String) {}
    /** Przychodzące połączenie: dzwonek i ekran (null = przestań dzwonić). */
    fun onCallRing(call: pl.szept.call.CallManager.Call?, name: String) {}
    /** Dzwoni rozmowa grupowa (null = przestań dzwonić). */
    fun onGroupRing(ring: pl.szept.call.GroupCallManager.Ring?, callerName: String) {}
}
