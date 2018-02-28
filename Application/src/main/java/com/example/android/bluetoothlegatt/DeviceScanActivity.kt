/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.bluetoothlegatt

import android.app.Activity
import android.app.ListActivity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast

/**
 * Activity for scanning and displaying available Bluetooth LE devices.
 */
class DeviceScanActivity : ListActivity() {
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mScanning: Boolean = false
    private var mHandler: Handler? = null

    // Device scan callback.
    private val mLeScanCallback = BluetoothAdapter.LeScanCallback { device, rssi, scanRecord ->
        runOnUiThread {
            // Only connect to LEGO Move Hubs
            if (device != null && device.name != null && device.name == "LEGO Move Hub") {
                val intent = Intent(this, DeviceControlActivity::class.java)
                intent.putExtra(DeviceControlActivity.EXTRAS_DEVICE_NAME, device.name)
                intent.putExtra(DeviceControlActivity.EXTRAS_DEVICE_ADDRESS, device.address)
                if (mScanning) {
                    mBluetoothAdapter!!.stopLeScan(null)
                    mScanning = false
                }
                startActivity(intent)
            }
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar!!.setTitle(R.string.title_devices)
        mHandler = Handler()

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show()
            finish()
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mBluetoothAdapter = bluetoothManager.adapter

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        if (!mScanning) {
            menu.findItem(R.id.menu_stop).isVisible = false
            menu.findItem(R.id.menu_scan).isVisible = true
            menu.findItem(R.id.menu_refresh).actionView = null
        } else {
            menu.findItem(R.id.menu_stop).isVisible = true
            menu.findItem(R.id.menu_scan).isVisible = false
            menu.findItem(R.id.menu_refresh).setActionView(
                    R.layout.actionbar_indeterminate_progress)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_scan -> {
                scanLeDevice(true)
            }
            R.id.menu_stop -> scanLeDevice(false)
        }
        return true
    }

    override fun onResume() {
        super.onResume()

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter!!.isEnabled) {
            if (!mBluetoothAdapter!!.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }

        scanLeDevice(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onPause() {
        super.onPause()
        scanLeDevice(false)
    }

    private fun scanLeDevice(enable: Boolean) {
        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler!!.postDelayed({
                mScanning = false
                mBluetoothAdapter!!.stopLeScan(mLeScanCallback)
                invalidateOptionsMenu()
            }, SCAN_PERIOD)

            mScanning = true
            mBluetoothAdapter!!.startLeScan(mLeScanCallback)
        } else {
            mScanning = false
            mBluetoothAdapter!!.stopLeScan(mLeScanCallback)
        }
        invalidateOptionsMenu()
    }

    companion object {

        private val REQUEST_ENABLE_BT = 1
        // Stops scanning after 10 seconds.
        private val SCAN_PERIOD: Long = 10000
    }
}