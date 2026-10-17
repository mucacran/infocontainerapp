package com.mucacran.infocontainer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mucacran.infocontainer.network.ConnectionProbe
import com.mucacran.infocontainer.network.ConnectionResult
import com.mucacran.infocontainer.network.WordPressConnectionProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Screen-session memory only. Deliberately has no SavedStateHandle or persistence. */
class ConnectionState(
    private val probe: ConnectionProbe = WordPressConnectionProbe()
) : ViewModel() {
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var isChecking by mutableStateOf(false)
        private set
    var result by mutableStateOf<ConnectionResult?>(null)
        private set
    private var request: Job? = null
    private var generation = 0L

    val canTest: Boolean
        get() = !isChecking && username.isNotBlank() && password.isNotBlank()

    fun updateUsername(value: String) {
        if (isChecking) return
        username = value
        result = null
    }

    fun updatePassword(value: String) {
        if (isChecking) return
        password = value
        result = null
    }

    fun open() = close()

    fun close() {
        generation++
        request?.cancel()
        request = null
        username = ""
        password = ""
        result = null
        isChecking = false
    }

    fun testConnection() {
        if (!canTest) return
        isChecking = true
        result = null
        val currentGeneration = generation
        // Starts synchronously through the first suspension: repeated taps cannot queue work.
        request = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val response = probe.check(username, password)
                if (generation == currentGeneration) result = response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never expose exception messages, URLs, headers or response bodies to the UI/log.
                if (generation == currentGeneration) result = ConnectionResult.UNAVAILABLE
            } finally {
                if (generation == currentGeneration) isChecking = false
            }
        }
    }

    override fun onCleared() {
        close()
    }
}
