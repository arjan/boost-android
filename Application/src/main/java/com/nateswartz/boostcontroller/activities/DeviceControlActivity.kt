package com.nateswartz.boostcontroller.activities

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.support.v13.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_device_control.*
import android.widget.*
import com.nateswartz.boostcontroller.*
import com.nateswartz.boostcontroller.controllers.LifxController
import com.nateswartz.boostcontroller.enums.BoostSensor
import com.nateswartz.boostcontroller.fragments.ActionsFragment
import com.nateswartz.boostcontroller.fragments.NotificationSettingsFragment
import com.nateswartz.boostcontroller.fragments.SpheroFragment
import com.nateswartz.boostcontroller.misc.SpheroServiceListener
import com.nateswartz.boostcontroller.notifications.HubNotification
import com.nateswartz.boostcontroller.notifications.PortDisconnectedNotification
import com.nateswartz.boostcontroller.notifications.PortConnectedNotification
import com.nateswartz.boostcontroller.notifications.listeners.*
import com.nateswartz.boostcontroller.services.LegoBluetoothDeviceService
import com.nateswartz.boostcontroller.services.SpheroProviderService
import com.orbotix.ConvenienceRobot
import com.orbotix.common.RobotChangedStateListener


class DeviceControlActivity : Activity(),
        SpheroServiceListener,
        NotificationSettingsFragment.OnFragmentInteractionListener,
        SpheroFragment.SpheroFragmentListener {

    private var notificationSettingsFragment: NotificationSettingsFragment? = null
    private var actionsFragment: ActionsFragment? = null
    private var spheroFragment: SpheroFragment? = null

    // Lifx
    private var lifxController: LifxController? = null

    // Sphero
    private var isSpheroServiceBound = false
    private var sphero: ConvenienceRobot? = null

    private var legoBluetoothDeviceService: LegoBluetoothDeviceService? = null
    private var connectedBoost = false
    private var connectingBoost = false

    private var connectedLpf2 = false
    private var connectingLpf2 = false

    private var notificationListeners = mutableMapOf<String, HubNotificationListener>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            legoBluetoothDeviceService = (service as LegoBluetoothDeviceService.LocalBinder).service
            notificationSettingsFragment!!.setLegoBluetoothDeviceService(legoBluetoothDeviceService!!)
            actionsFragment!!.setLegoBluetoothDeviceService(legoBluetoothDeviceService!!)
            finishSetup()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            Log.d(TAG, "Service Disconnect")
            legoBluetoothDeviceService = null
            actionsFragment!!.setLegoBluetoothDeviceService(null)
        }
    }

    private val moveHubUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                LegoBluetoothDeviceService.ACTION_BOOST_CONNECTED -> {
                    connectedBoost = true
                    connectingBoost = false
                    notificationSettingsFragment!!.boostConnectionChanged(connectedBoost)
                    actionsFragment!!.boostConnectionChanged(connectedBoost)
                    enableControls()
                    invalidateOptionsMenu()
                }
                LegoBluetoothDeviceService.ACTION_BOOST_DISCONNECTED -> {
                    connectedBoost = false
                    connectingBoost = false
                    notificationSettingsFragment!!.boostConnectionChanged(connectedBoost)
                    actionsFragment!!.boostConnectionChanged(connectedBoost)
                    disableControls()
                    invalidateOptionsMenu()
                }
                LegoBluetoothDeviceService.ACTION_LPF2_CONNECTED -> {
                    connectedLpf2 = true
                    connectingLpf2 = false
                    notificationSettingsFragment!!.lpf2ConnectionChanged(connectedLpf2)
                }
                LegoBluetoothDeviceService.ACTION_LPF2_DISCONNECTED -> {
                    connectedLpf2 = false
                    connectingLpf2 = false
                    notificationSettingsFragment!!.lpf2ConnectionChanged(connectedLpf2)
                }
                LegoBluetoothDeviceService.ACTION_DEVICE_CONNECTION_FAILED -> {
                    connectingBoost = false
                    connectingLpf2 = false
                    invalidateOptionsMenu()
                    Toast.makeText(this@DeviceControlActivity, "Connection Failed!", Toast.LENGTH_SHORT).show()
                }
                LegoBluetoothDeviceService.ACTION_DEVICE_NOTIFICATION -> {
                    val notification = intent.getParcelableExtra<HubNotification>(LegoBluetoothDeviceService.NOTIFICATION_DATA)

                    if (notification is PortConnectedNotification) {
                        when (notification.sensor) {
                            BoostSensor.DISTANCE_COLOR -> notificationSettingsFragment!!.colorSensorConnectionChanged(true)
                            BoostSensor.EXTERNAL_MOTOR -> notificationSettingsFragment!!.externalMotorConnectionChanged(true)
                        }
                    }
                    if (notification is PortDisconnectedNotification) {
                        when (notification.sensor) {
                            BoostSensor.EXTERNAL_MOTOR -> notificationSettingsFragment!!.externalMotorConnectionChanged(false)
                            BoostSensor.DISTANCE_COLOR -> notificationSettingsFragment!!.colorSensorConnectionChanged(false)
                        }
                    }
                    for (listener in notificationListeners) {
                        listener.value.execute(notification)
                    }
                }
            }
        }
    }

    private val spheroServiceConnection = object : ServiceConnection {
        private var boundService: SpheroProviderService? = null

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d(TAG,"onServiceConnected")
            boundService = (service as SpheroProviderService.RobotBinder).service
            isSpheroServiceBound = true
            boundService?.addListener(this@DeviceControlActivity)
            if (boundService?.hasActiveSphero() == true) {
                handleSpheroChange(boundService!!.getSphero(), RobotChangedStateListener.RobotChangedStateNotificationType.Online)
            } else {
                val toast = Toast.makeText(this@DeviceControlActivity, "Discovering...",
                        Toast.LENGTH_LONG)
                toast.setGravity(Gravity.BOTTOM, 0, 10)
                toast.show()
            }
            spheroFragment!!.spheroServiceBound()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            Log.e(TAG,"onServiceDisconnected")
            boundService = null
            isSpheroServiceBound = false
            boundService?.removeListener(this@DeviceControlActivity)
            spheroFragment!!.spheroServiceUnbound()
        }
    }

    override fun handleSpheroChange(robot: ConvenienceRobot, type: RobotChangedStateListener.RobotChangedStateNotificationType) {
        when (type) {
            RobotChangedStateListener.RobotChangedStateNotificationType.Online -> {
                Log.d(TAG, "handleRobotOnline")
                val toast = Toast.makeText(this@DeviceControlActivity, "Connected!",
                        Toast.LENGTH_LONG)
                toast.setGravity(Gravity.BOTTOM, 0, 10)
                toast.show()
                sphero = robot
                spheroFragment!!.spheroOnline()
            }
            RobotChangedStateListener.RobotChangedStateNotificationType.Offline -> {
                Log.d(TAG, "handleRobotDisconnected")
                sphero = null
                spheroFragment!!.spheroOffline()
            }
            RobotChangedStateListener.RobotChangedStateNotificationType.Connecting -> {
                Log.d(TAG, "handleRobotConnecting")
                val toast = Toast.makeText(this@DeviceControlActivity, "Connecting..",
                        Toast.LENGTH_LONG)
                toast.setGravity(Gravity.BOTTOM, 0, 10)
                toast.show()
            }
            else -> {
            }
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_device_control)

        lifxController = LifxController(this)

        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE)
        } else {
            val moveHubServiceIntent = Intent(this, LegoBluetoothDeviceService::class.java)
            bindService(moveHubServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    val moveHubServiceIntent = Intent(this, LegoBluetoothDeviceService::class.java)
                    bindService(moveHubServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

                    return
                }
            }
        }
    }

    public override fun onStart() {
        super.onStart()
        notificationSettingsFragment = fragmentManager.findFragmentById(R.id.notifications_fragement) as NotificationSettingsFragment
        actionsFragment = fragmentManager.findFragmentById(R.id.actions_fragment) as ActionsFragment
        fragmentManager
                .beginTransaction()
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                .hide(actionsFragment)
                .commit()
        spheroFragment = fragmentManager.findFragmentById(R.id.sphero_fragment) as SpheroFragment
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        if (connectedBoost || connectedLpf2) {
            menu.findItem(R.id.menu_disconnect).isVisible = true
            menu.findItem(R.id.menu_connect).isVisible = !(connectedBoost && connectedLpf2)
        } else {
            menu.findItem(R.id.menu_connect).isVisible = true
            menu.findItem(R.id.menu_disconnect).isVisible = false
            if (connectingBoost || connectingLpf2) {
                menu.findItem(R.id.menu_connect).isEnabled = false
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_connect -> {
                Log.d(TAG, "Connecting...")
                Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show()
                connectingBoost = !connectedBoost
                connectingLpf2 = !connectedLpf2
                legoBluetoothDeviceService!!.connect()
                return true
            }
            R.id.menu_disconnect -> {
                Log.d(TAG, "Disconnecting...")
                legoBluetoothDeviceService!!.disconnect()
                connectedBoost = false
                connectedLpf2 = false
                notificationSettingsFragment!!.boostConnectionChanged(connectedBoost)
                notificationSettingsFragment!!.lpf2ConnectionChanged(connectedLpf2)
                actionsFragment!!.boostConnectionChanged(connectedBoost)
                invalidateOptionsMenu()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "onActivityResult Enter")
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun finishSetup() {
        Log.d(TAG, "FinishSetup")
        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show()
            finish()
        }

        connectingBoost = true
        connectingLpf2 = true
        legoBluetoothDeviceService!!.connect()

        disableControls()

        switch_sync_colors.setOnClickListener {
            when (switch_button_change_light.isChecked) {
                true -> registerListener(ChangeLEDOnColorSensor(legoBluetoothDeviceService!!), "sync_colors")
                false -> unregisterListener("sync_colors")
            }
        }

        switch_button_change_light.setOnClickListener {
            when (switch_button_change_light.isChecked) {
                true -> registerListener(ChangeLEDOnButtonClick(legoBluetoothDeviceService!!), "button_change_light")
                false -> unregisterListener("button_change_light")
            }
        }

        switch_button_change_motor.setOnClickListener {
            when (switch_button_change_motor.isChecked) {
                true -> registerListener(RunMotorOnButtonClick(legoBluetoothDeviceService!!), "button_change_motor")
                false -> unregisterListener("button_change_motor")
            }
        }

        switch_tilt_led.setOnClickListener {
            when (switch_tilt_led.isChecked) {
                true -> registerListener(ChangeLEDColorOnTilt(legoBluetoothDeviceService!!), "tilt_change_led")
                false -> unregisterListener("tilt_change_led")
            }
        }

        switch_roller_coaster.setOnClickListener {
            when (switch_roller_coaster.isChecked) {
                true -> registerListener(RollerCoaster("2000", true, legoBluetoothDeviceService!!), "roller_coaster")
                false -> unregisterListener("roller_coaster")
            }
        }

        switch_motor_button_lifx.setOnClickListener {
            when (switch_button_change_motor.isChecked) {
                true -> registerListener(ChangeLifxLEDOnMotorButton(legoBluetoothDeviceService!!, lifxController!!), "motor_button_led_lifx")
                false -> unregisterListener("motor_button_led_lifx")
            }
        }

        textview_notifications.setOnClickListener {
            val transaction = fragmentManager.beginTransaction()
                                    .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
            if (notificationSettingsFragment!!.isHidden){
                transaction.show(notificationSettingsFragment)
            } else {
                transaction.hide(notificationSettingsFragment)
            }
            transaction.commit()
        }

        textview_actions.setOnClickListener {
            val transaction = fragmentManager.beginTransaction()
                    .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
            if (actionsFragment!!.isHidden){
                transaction.show(actionsFragment)
            } else {
                transaction.hide(actionsFragment)
            }
            transaction.commit()
        }

        textview_sphero.setOnClickListener {
            val transaction = fragmentManager.beginTransaction()
                    .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
            if (spheroFragment!!.isHidden){
                transaction.show(spheroFragment)
            } else {
                transaction.hide(spheroFragment)
            }
            transaction.commit()
        }
    }

    override fun onConnect() {
        Toast.makeText(this, "Connecting to Sphero...", Toast.LENGTH_SHORT).show()
        val spheroServiceIntent = Intent(this, SpheroProviderService::class.java)
        bindService(spheroServiceIntent, spheroServiceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDisconnect() {
        Toast.makeText(this, "Disconnecting Sphero...", Toast.LENGTH_SHORT).show()
        unbindService(spheroServiceConnection)
        isSpheroServiceBound = false
        spheroFragment!!.spheroServiceUnbound()
    }

    override fun onButtonLEDListenerChange(enabled: Boolean) {
        when (enabled) {
            true -> registerListener(ChangeSpheroColorOnButton(legoBluetoothDeviceService!!, sphero!!), "button_sphero")
            false -> unregisterListener("button_sphero")
        }
    }

    override fun onTiltLEDListenerChange(enabled: Boolean) {
        when (enabled) {
            true -> registerListener(ChangeSpheroColorOnTilt(legoBluetoothDeviceService!!, sphero!!), "tilt_sphero")
            false -> unregisterListener("tilt_sphero")
        }
    }

    private fun registerListener(listener: HubNotificationListener, label: String) {
        listener.setup()
        notificationListeners[label] = listener
    }

    private fun unregisterListener(label: String) {
        val listener = notificationListeners[label]
        listener!!.cleanup()
        notificationListeners.remove(label)
    }


    private fun enableControls() {
        setControlsState(true)
    }

    private fun disableControls() {
        setControlsState(false)
    }

    private fun setControlsState(enabled : Boolean) {
        switch_sync_colors.isEnabled = enabled
        switch_button_change_motor.isEnabled = enabled
        switch_button_change_light.isEnabled = enabled
        switch_motor_button_lifx.isEnabled = enabled
        switch_roller_coaster.isEnabled = enabled
    }

    override fun onResume() {
        super.onResume()

        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE)
        } else {
            // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
            // fire an intent to display a dialog asking the user to grant permission to enable it.
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            if (!bluetoothAdapter!!.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }

        registerReceiver(moveHubUpdateReceiver, makeMoveHubUpdateIntentFilter())
        if (legoBluetoothDeviceService != null) {
            val result = legoBluetoothDeviceService!!.connect()
            Log.d(TAG, "Connect request result=$result")
        }

    }

    override fun onPause() {
        super.onPause()
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {

            if (connectingBoost || connectedBoost || connectingLpf2 || connectedLpf2) {
                legoBluetoothDeviceService!!.disconnect()
                connectedBoost = false
                connectingBoost = false
                connectedLpf2 = false
                connectingLpf2 = false
                notificationSettingsFragment!!.boostConnectionChanged(connectedBoost)
                actionsFragment!!.boostConnectionChanged(connectedBoost)
                notificationSettingsFragment!!.lpf2ConnectionChanged(connectedLpf2)
            }
        }
        unregisterReceiver(moveHubUpdateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
        if (isSpheroServiceBound) {
            unbindService(spheroServiceConnection)
        }
        legoBluetoothDeviceService = null
    }

    companion object {
        private val TAG = DeviceControlActivity::class.java.simpleName

        const val PERMISSION_REQUEST_CODE = 1

        const val REQUEST_ENABLE_BT = 1
        // Stops scanning after 10 seconds.
        const val SCAN_PERIOD: Long = 10000

        private fun makeMoveHubUpdateIntentFilter(): IntentFilter {
            val intentFilter = IntentFilter()
            intentFilter.addAction(LegoBluetoothDeviceService.ACTION_BOOST_CONNECTED)
            intentFilter.addAction(LegoBluetoothDeviceService.ACTION_BOOST_DISCONNECTED)
            intentFilter.addAction(LegoBluetoothDeviceService.ACTION_LPF2_CONNECTED)
            intentFilter.addAction(LegoBluetoothDeviceService.ACTION_LPF2_DISCONNECTED)
            intentFilter.addAction(LegoBluetoothDeviceService.ACTION_DEVICE_CONNECTION_FAILED)
            intentFilter.addAction(LegoBluetoothDeviceService.ACTION_DEVICE_NOTIFICATION)
            return intentFilter
        }
    }
}
