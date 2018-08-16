package com.nateswartz.boostcontroller

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
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.support.v13.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import kotlinx.android.synthetic.main.activity_device_control.*
import com.orbotix.DualStackDiscoveryAgent
import android.widget.*
import com.orbotix.ConvenienceRobot
import com.orbotix.common.DiscoveryException
import com.orbotix.common.Robot
import com.orbotix.common.RobotChangedStateListener


class DeviceControlActivity : Activity(), AdapterView.OnItemSelectedListener, RobotChangedStateListener, NotificationSettingsFragment.OnFragmentInteractionListener {
    private var notificationSettingsFragment: NotificationSettingsFragment? = null

    // Lifx
    private var lifxController: LifxController? = null

    // Sphero
    private val mDiscoveryAgent = DualStackDiscoveryAgent()
    private var mRobot: ConvenienceRobot? = null

    private var bluetoothDeviceService: BluetoothDeviceService? = null
    private var connectedBoost = false
    private var connectingBoost = false

    private var connectedLpf2 = false
    private var connectingLpf2 = false

    private var colorArray = arrayOf("Off", "Blue", "Pink", "Purple",
            "Light Blue", "Cyan", "Green", "Yellow", "Orange", "Red", "White")

    private var motorTypes = arrayOf("A", "B", "A+B", "External")

    private var notificationListeners = mutableMapOf<String, HubNotificationListener>()

    private val PERMISSION_REQUEST_CODE = 1

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            bluetoothDeviceService = (service as BluetoothDeviceService.LocalBinder).service
            finishSetup()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            Log.d(TAG, "Service Disconnect")
            bluetoothDeviceService = null
        }
    }

    private val moveHubUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                BluetoothDeviceService.ACTION_BOOST_CONNECTED -> {
                    connectedBoost = true
                    connectingBoost = false
                    notificationSettingsFragment!!.boostConnectionChanged(connectedBoost)
                    bluetoothDeviceService!!.moveHubController.enableNotifications()
                    enableControls()
                    invalidateOptionsMenu()
                }
                BluetoothDeviceService.ACTION_BOOST_DISCONNECTED -> {
                    connectedBoost = false
                    connectingBoost = false
                    notificationSettingsFragment!!.boostConnectionChanged(connectedBoost)
                    disableControls()
                    invalidateOptionsMenu()
                }
                BluetoothDeviceService.ACTION_LPF2_CONNECTED -> {
                    connectedLpf2 = true
                    connectingLpf2 = false
                    notificationSettingsFragment!!.lpf2ConnectionChanged(connectedLpf2)
                }
                BluetoothDeviceService.ACTION_LPF2_DISCONNECTED -> {
                    connectedLpf2 = false
                    connectingLpf2 = false
                    notificationSettingsFragment!!.lpf2ConnectionChanged(connectedLpf2)
                }
                BluetoothDeviceService.ACTION_DEVICE_CONNECTION_FAILED -> {
                    connectingBoost = false
                    connectingLpf2 = false
                    invalidateOptionsMenu()
                    Toast.makeText(this@DeviceControlActivity, "Connection Failed!", Toast.LENGTH_SHORT).show()
                }
                BluetoothDeviceService.ACTION_DEVICE_NOTIFICATION -> {
                    val notification = intent.getParcelableExtra<HubNotification>(BluetoothDeviceService.NOTIFICATION_DATA)

                    for (listener in notificationListeners) {
                        listener.value.execute(notification)
                    }
                }
            }
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifxController = LifxController(this)

        mDiscoveryAgent.addRobotStateListener(this)

        setContentView(R.layout.activity_device_control)

        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE)

        } else {
            val moveHubServiceIntent = Intent(this, BluetoothDeviceService::class.java)
            bindService(moveHubServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    val moveHubServiceIntent = Intent(this, BluetoothDeviceService::class.java)
                    bindService(moveHubServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                    return
                }
            }
        }
    }

    public override fun onStart() {
        super.onStart()
        notificationSettingsFragment = fragmentManager.findFragmentById(R.id.notifications_fragement) as NotificationSettingsFragment
    }

    // Sphero
    private fun startDiscovery() {
        //If the DiscoveryAgent is not already looking for robots, start discovery.
        if( !mDiscoveryAgent.isDiscovering ) {
            try {
                Log.d("Sphero", "Looking for Sphero")
                mDiscoveryAgent.startDiscovery(applicationContext)
            } catch (e: DiscoveryException) {
                Log.e("Sphero", "DiscoveryException: " + e.message)
            }
        }
    }

    // Sphero
    override fun handleRobotChangedState(robot: Robot, type: RobotChangedStateListener.RobotChangedStateNotificationType) {
        Log.d("Sphero", "handleRobotChangedState $type")
        when (type) {
            RobotChangedStateListener.RobotChangedStateNotificationType.Connected -> {
                mRobot = ConvenienceRobot(robot)
                switch_sphero_color_button.isEnabled = true
                button_sphero_connect.isEnabled = false
            }
            RobotChangedStateListener.RobotChangedStateNotificationType.Online -> {
                mRobot = ConvenienceRobot(robot)
                switch_sphero_color_button.isEnabled = true
                button_sphero_connect.isEnabled = false
            }
        }
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
                bluetoothDeviceService!!.connect()
                return true
            }
            R.id.menu_disconnect -> {
                Log.d(TAG, "Disconnecting...")
                bluetoothDeviceService!!.disconnect()
                connectedBoost = false
                connectedLpf2 = false
                notificationSettingsFragment!!.boostConnectionChanged(connectedBoost)
                notificationSettingsFragment!!.lpf2ConnectionChanged(connectedLpf2)
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
        bluetoothDeviceService!!.connect()

        val colorAdapter = ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, colorArray)
        colorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner_led_colors.adapter = colorAdapter
        spinner_led_colors.onItemSelectedListener = this
        // Set Spinner to Blue to start (since that's the Hub default)
        spinner_led_colors.setSelection(1)

        val motorAdapter = ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, motorTypes)
        motorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner_motor_types.adapter = motorAdapter
        spinner_motor_types.onItemSelectedListener = this

        disableControls()
        switch_sphero_color_button.isEnabled = false

        checkbox_color_sensor.setOnClickListener{
            if (checkbox_color_sensor.isChecked) {
                bluetoothDeviceService!!.moveHubController.activateColorSensorNotifications()
            } else {
                bluetoothDeviceService!!.moveHubController.deactivateColorSensorNotifications()
            }
        }

        checkbox_tilt_sensor.setOnClickListener {
            if (checkbox_tilt_sensor.isChecked) {
                bluetoothDeviceService!!.moveHubController.activateTiltSensorNotifications()
            } else {
                bluetoothDeviceService!!.moveHubController.deactivateTiltSensorNotifications()
            }
        }

        checkbox_internal_motors.setOnClickListener {
            if (checkbox_internal_motors.isChecked) {
                bluetoothDeviceService!!.moveHubController.activateInternalMotorSensorsNotifications()
            } else {
                bluetoothDeviceService!!.moveHubController.deactivateInternalMotorSensorsNotifications()
            }
        }

        checkbox_external_motor.setOnClickListener {
            if (checkbox_external_motor.isChecked) {
                bluetoothDeviceService!!.moveHubController.activateExternalMotorSensorNotifications()
            } else {
                bluetoothDeviceService!!.moveHubController.deactivateExternalMotorSensorNotifications()
            }
        }

        checkbox_button.setOnClickListener {
            if (checkbox_button.isChecked) {
                bluetoothDeviceService!!.moveHubController.activateButtonNotifications()
            } else {
                // Currently not working
                bluetoothDeviceService!!.moveHubController.deactivateButtonNotifications()
            }
        }

        button_spin.setOnClickListener {
            bluetoothDeviceService!!.moveHubController.runInternalMotorsInOpposition(20, 300)
        }

        button_var_run_motor.setOnClickListener {
            val power = input_power.text.toString()
            val time = input_time.text.toString()
            val motor = spinner_motor_types.selectedItem.toString()
            val counterclockwise = switch_counter_clockwise.isChecked
            if (power != "" && time != "") {
                when (motor) {
                    "A" -> bluetoothDeviceService!!.moveHubController.runInternalMotor(power.toInt(), time.toInt(), counterclockwise, "A")
                    "B" -> bluetoothDeviceService!!.moveHubController.runInternalMotor(power.toInt(), time.toInt(), counterclockwise, "B")
                    "A+B" -> bluetoothDeviceService!!.moveHubController.runInternalMotors(power.toInt(), time.toInt(), counterclockwise)
                    "External" -> bluetoothDeviceService!!.moveHubController.runExternalMotor(power.toInt(), time.toInt(), counterclockwise)
                }
            }
        }

        // Sphero
        button_sphero_connect.setOnClickListener {
            Toast.makeText(this, "Connecting to Sphero...", Toast.LENGTH_SHORT).show()
            startDiscovery()
        }

        switch_sphero_color_button.setOnClickListener {
            if (switch_sphero_color_button.isChecked) {
                notificationListeners["button_sphero"] = ChangeSpheroColorOnButton(mRobot!!)
            } else {
                notificationListeners.remove("button_sphero")
            }
        }

        switch_sync_colors.setOnClickListener {
            if (switch_sync_colors.isChecked) {
                notificationListeners["sync_colors"] = ChangeLEDOnColorSensor(bluetoothDeviceService!!)
            } else {
                notificationListeners.remove("sync_colors")
            }
        }

        switch_button_change_light.setOnClickListener {
            if (switch_button_change_light.isChecked) {
                notificationListeners["button_change_light"] = ChangeLEDOnButtonClick(bluetoothDeviceService!!)
            } else {
                notificationListeners.remove("button_change_light")
            }
        }

        switch_button_change_motor.setOnClickListener {
            if (switch_button_change_motor.isChecked) {
                notificationListeners["button_change_motor"] = RunMotorOnButtonClick(bluetoothDeviceService!!)
            } else {
                notificationListeners.remove("button_change_motor")
            }
        }

        switch_roller_coaster.setOnClickListener {
            if (switch_roller_coaster.isChecked) {
                notificationListeners["roller_coaster"] = RollerCoaster(input_time.text.toString(), switch_counter_clockwise.isChecked, bluetoothDeviceService!!)
            } else {
                notificationListeners.remove("roller_coaster")
            }
        }

        switch_motor_button_lifx.setOnClickListener {
            if (switch_motor_button_lifx.isChecked) {
                notificationListeners["motor_button_led_lifx"] = ChangeLifxLEDOnMotorButton(bluetoothDeviceService!!, lifxController!!)
            } else {
                notificationListeners.remove("motor_button_led_lifx")
            }
        }
    }

    override fun onChangeAllNotifications() {
        checkbox_button.performClick()
        checkbox_internal_motors.performClick()
        checkbox_external_motor.performClick()
        checkbox_color_sensor.performClick()
        checkbox_tilt_sensor.performClick()
    }

    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (connectedBoost) {
            when (parent) {
                spinner_led_colors -> {
                    val item = parent!!.getItemAtPosition(position).toString()
                    val color = getLedColorFromName(item)
                    bluetoothDeviceService?.moveHubController?.setLEDColor(color)
                }
            }
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
    }

    private fun enableControls() {
        setControlsState(true)
    }

    private fun disableControls() {
        setControlsState(false)
    }

    private fun setControlsState(enabled : Boolean) {
        button_spin.isEnabled = enabled
        spinner_led_colors.isEnabled = enabled
        spinner_motor_types.isEnabled = enabled
        text_power.isEnabled = enabled
        text_time.isEnabled = enabled
        input_power.isEnabled = enabled
        input_time.isEnabled = enabled
        button_var_run_motor.isEnabled = enabled
        switch_counter_clockwise.isEnabled = enabled
        switch_sync_colors.isEnabled = enabled
        checkbox_color_sensor.isEnabled = enabled
        checkbox_tilt_sensor.isEnabled = enabled
        checkbox_button.isEnabled = enabled
        checkbox_external_motor.isEnabled = enabled
        checkbox_internal_motors.isEnabled = enabled
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
        if (bluetoothDeviceService != null) {
            val result = bluetoothDeviceService!!.connect()
            Log.d(TAG, "Connect request result=$result")
        }

    }

    override fun onPause() {
        super.onPause()
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {

            if (connectingBoost || connectedBoost || connectingLpf2 || connectedLpf2) {
                bluetoothDeviceService!!.disconnect()
                connectedBoost = false
                connectingBoost = false
                connectedLpf2 = false
                connectingLpf2 = false
                notificationSettingsFragment!!.boostConnectionChanged(connectedBoost)
                notificationSettingsFragment!!.lpf2ConnectionChanged(connectedLpf2)
            }
        }
        unregisterReceiver(moveHubUpdateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
        bluetoothDeviceService = null

        // Sphero
        // If the DiscoveryAgent is in discovery mode, stop it.
        if (mDiscoveryAgent.isDiscovering) {
            mDiscoveryAgent.stopDiscovery()
        }

        // If a robot is connected to the device, disconnect it
        mRobot?.disconnect()
        mRobot = null
        switch_sphero_color_button.isEnabled = false
    }

    companion object {
        private val TAG = DeviceControlActivity::class.java.simpleName

        const val REQUEST_ENABLE_BT = 1
        // Stops scanning after 10 seconds.
        const val SCAN_PERIOD: Long = 10000

        private fun makeMoveHubUpdateIntentFilter(): IntentFilter {
            val intentFilter = IntentFilter()
            intentFilter.addAction(BluetoothDeviceService.ACTION_BOOST_CONNECTED)
            intentFilter.addAction(BluetoothDeviceService.ACTION_BOOST_DISCONNECTED)
            intentFilter.addAction(BluetoothDeviceService.ACTION_LPF2_CONNECTED)
            intentFilter.addAction(BluetoothDeviceService.ACTION_LPF2_DISCONNECTED)
            intentFilter.addAction(BluetoothDeviceService.ACTION_DEVICE_CONNECTION_FAILED)
            intentFilter.addAction(BluetoothDeviceService.ACTION_DEVICE_NOTIFICATION)
            return intentFilter
        }
    }
}
