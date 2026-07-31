package com.example.meshchat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Date

class MainActivity : AppCompatActivity(), BleMeshManager.MeshListener {

    private lateinit var meshManager: BleMeshManager
    private lateinit var statusText: TextView
    private lateinit var messageInput: EditText
    private lateinit var messageList: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val messages = mutableListOf<String>()

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startMesh()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required for mesh chat", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        messageInput = findViewById(R.id.messageInput)
        messageList = findViewById(R.id.messageList)
        val sendButton = findViewById<Button>(R.id.sendButton)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, messages)
        messageList.adapter = adapter

        meshManager = BleMeshManager(applicationContext, this)

        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                meshManager.sendMessage(text)
                messageInput.text.clear()
            }
        }

        ensurePermissionsAndStart()
    }

    private fun ensurePermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startMesh()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startMesh() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) {
            statusText.text = "Status: please enable Bluetooth"
            Toast.makeText(this, "Turn on Bluetooth and reopen the app", Toast.LENGTH_LONG).show()
            return
        }
        meshManager.start()
    }

    override fun onMessageReceived(text: String, timestamp: Long, hopRelayed: Boolean) {
        runOnUiThread {
            val time = DateFormat.format("HH:mm:ss", Date(timestamp))
            val tag = if (hopRelayed) "[relayed]" else "[you]"
            messages.add(0, "$time $tag $text")
            adapter.notifyDataSetChanged()
        }
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread { statusText.text = "Status: $status" }
    }

    override fun onPeerCountChanged(count: Int) {
        runOnUiThread { statusText.text = "Status: mesh active — $count peer(s) connected" }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::meshManager.isInitialized) meshManager.stop()
    }
}
