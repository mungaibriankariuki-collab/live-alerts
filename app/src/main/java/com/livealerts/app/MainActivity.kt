package com.livealerts.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livealerts.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val adapter = ChannelAdapter(onOpen = ::open, onRemove = ::confirmRemove)
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.KEY_CHANNELS) runOnUiThread { refresh() }
    }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.add.setOnClickListener { add() }
        b.input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) add()
            action == EditorInfo.IME_ACTION_DONE
        }
        b.input.doAfterTextChanged { b.inputLayout.error = null }

        b.fastSwitch.isChecked = Store.isFast(this)
        b.fastSwitch.setOnCheckedChangeListener { _, on ->
            Store.setFast(this, on)
            Scheduler.apply(this)
        }
        b.wifiSwitch.isChecked = Store.isWifiOnly(this)
        b.wifiSwitch.setOnCheckedChangeListener { _, on ->
            Store.setWifiOnly(this, on)
            Scheduler.apply(this)
            refresh()
        }
        b.checkNow.setOnClickListener { checkNow() }
        b.battery.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Notifier.createChannels(this)
        Scheduler.apply(this)
    }

    override fun onStart() {
        super.onStart()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
        val power = getSystemService(POWER_SERVICE) as PowerManager
        b.battery.visibility =
            if (power.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    private fun refresh() {
        val channels = Store.channels(this)
        adapter.submit(channels)
        b.empty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        b.count.text = getString(R.string.count, channels.size, Store.MAX_CHANNELS)
        val full = channels.size >= Store.MAX_CHANNELS
        b.inputLayout.isEnabled = !full
        b.add.isEnabled = !full
        b.inputLayout.helperText = if (full) getString(R.string.full) else null
        val last = channels.maxOfOrNull { it.lastChecked } ?: 0
        b.lastChecked.text = if (Checker.pausedForData(this)) getString(R.string.paused_for_data)
        else if (last == 0L) "" else getString(
            R.string.last_checked,
            DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
        )
    }

    private fun add() {
        val path = YouTube.parse(b.input.text?.toString().orEmpty())
        if (path == null) {
            b.inputLayout.error = getString(R.string.error_invalid)
            return
        }
        val existing = Store.channels(this)
        if (existing.size >= Store.MAX_CHANNELS) return
        if (existing.any { it.path.equals(path, ignoreCase = true) }) {
            b.inputLayout.error = getString(R.string.error_duplicate)
            return
        }
        b.add.isEnabled = false
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { YouTube.channelName(path) } }
            b.progress.visibility = View.GONE
            b.add.isEnabled = true
            result.onSuccess { name ->
                Store.update(this@MainActivity) { list ->
                    if (list.size >= Store.MAX_CHANNELS || list.any { it.path == path }) list
                    else list + Channel(path = path, name = name)
                }
                b.input.text = null
                Scheduler.apply(this@MainActivity)
                checkNow()
            }.onFailure { e ->
                b.inputLayout.error = getString(
                    if (e is ChannelNotFoundException) R.string.error_not_found else R.string.error_network
                )
            }
        }
    }

    private fun checkNow() {
        b.checkNow.isEnabled = false
        b.checkNow.setText(R.string.checking)
        lifecycleScope.launch {
            Checker.checkAll(applicationContext, force = true)
            b.checkNow.isEnabled = true
            b.checkNow.setText(R.string.check_now)
            refresh()
        }
    }

    private fun open(channel: Channel) {
        val url = channel.liveVideoId?.takeIf { channel.live }?.let(YouTube::watchUrl)
            ?: YouTube.channelUrl(channel.path)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRemove(channel: Channel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.remove_title, channel.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                Store.update(this) { list -> list.filterNot { it.path == channel.path } }
                NotificationManagerCompat.from(this).cancel(channel.path.hashCode())
                Scheduler.apply(this)
            }
            .show()
    }
}
