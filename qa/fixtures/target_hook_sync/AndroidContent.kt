package android.content

open class Context {
    open val applicationContext: Context get() = this
    open fun sendBroadcast(intent: Intent) {}
    open fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? = null
    open fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = null
    open fun unregisterReceiver(receiver: BroadcastReceiver) {}
    companion object { const val RECEIVER_EXPORTED = 2 }
}
abstract class BroadcastReceiver { abstract fun onReceive(context: Context?, intent: Intent?) }
class IntentFilter(val action: String)
class Intent(val action: String) {
    private var target: String? = null
    private val extras = mutableMapOf<String, String>()
    fun setPackage(value: String): Intent { target = value; return this }
    fun getPackage(): String? = target
    fun putExtra(key: String, value: String): Intent { extras[key] = value; return this }
    fun getStringExtra(key: String): String? = extras[key]
}
