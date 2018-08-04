package com.nateswartz.boostcontroller

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.widget.Toast


/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
class MoveHubController (private val gattController: GattController) {

    private val ACTIVATE_BUTTON = byteArrayOf(0x05, 0x00, 0x01, 0x02, 0x02)
    // Currently not working
    private val DEACTIVATE_BUTTON = byteArrayOf(0x05, 0x00, 0x03, 0x02, 0x00)

    private val ACTIVATE_COLOR_SENSOR_PORT_C = byteArrayOf(0x0a, 0x00, 0x41, 0x01, 0x08, 0x01, 0x00, 0x00, 0x00, 0x01)
    private val ACTIVATE_COLOR_SENSOR_PORT_D = byteArrayOf(0x0a, 0x00, 0x41, 0x02, 0x08, 0x01, 0x00, 0x00, 0x00, 0x01)
    private val DEACTIVATE_COLOR_SENSOR_PORT_C = byteArrayOf(0x0a, 0x00, 0x41, 0x01, 0x08, 0x01, 0x00, 0x00, 0x00, 0x00)
    private val DEACTIVATE_COLOR_SENSOR_PORT_D = byteArrayOf(0x0a, 0x00, 0x41, 0x02, 0x08, 0x01, 0x00, 0x00, 0x00, 0x00)

    private val ACTIVATE_EXTERNAL_MOTOR_PORT_C = byteArrayOf(0x0a, 0x00, 0x41, 0x01, 0x02, 0x01, 0x00, 0x00, 0x00, 0x01)
    private val ACTIVATE_EXTERNAL_MOTOR_PORT_D = byteArrayOf(0x0a, 0x00, 0x41, 0x02, 0x02, 0x01, 0x00, 0x00, 0x00, 0x01)
    private val DEACTIVATE_EXTERNAL_MOTOR_PORT_C = byteArrayOf(0x0a, 0x00, 0x41, 0x01, 0x02, 0x01, 0x00, 0x00, 0x00, 0x00)
    private val DEACTIVATE_EXTERNAL_MOTOR_PORT_D = byteArrayOf(0x0a, 0x00, 0x41, 0x02, 0x02, 0x01, 0x00, 0x00, 0x00, 0x00)

    private val ACTIVATE_MOTOR_PORT = byteArrayOf(0x0a, 0x00, 0x41, 0x00, 0x02, 0x01, 0x00, 0x00, 0x00, 0x01)
    private val DEACTIVATE_MOTOR_PORT = byteArrayOf(0x0a, 0x00, 0x41, 0x00, 0x02, 0x01, 0x00, 0x00, 0x00, 0x00)

    private val ACTIVATE_TILT_SENSOR = byteArrayOf(0x0a, 0x00, 0x41, 0x3a, 0x02, 0x01, 0x00, 0x00, 0x00, 0x01)
    private val DEACTIVATE_TILT_SENSOR = byteArrayOf(0x0a, 0x00, 0x41, 0x3a, 0x02, 0x01, 0x00, 0x00, 0x00, 0x00)

    private val C_PORT_BYTE = 0x01.toByte()
    private val D_PORT_BYTE = 0x02.toByte()
    private val AB_PORT_BYTE = 0x39.toByte()
    private val A_PORT_BYTE = 0x37.toByte()
    private val B_PORT_BYTE = 0x38.toByte()

    var ColorSensorPort = ""
    var ExternalMotorPort = ""

    fun setLEDColor(color: LEDColorCommand) {
        gattController.writeCharacteristic(color.data)
    }

    fun runExternalMotor(powerPercentage: Int, timeInMilliseconds: Int, counterclockwise: Boolean) {
        var portByte : Byte? = null
        if (ExternalMotorPort == "C") {
            portByte = C_PORT_BYTE
        } else if (ExternalMotorPort == "D") {
            portByte = D_PORT_BYTE
        }
        runMotor(powerPercentage, timeInMilliseconds, counterclockwise, portByte!!)
    }

    fun runInternalMotor(powerPercentage: Int, timeInMilliseconds: Int, counterclockwise: Boolean, motor: String)
    {
        when (motor) {
            "A" -> runMotor(powerPercentage, timeInMilliseconds, counterclockwise, A_PORT_BYTE)
            "B" -> runMotor(powerPercentage, timeInMilliseconds, counterclockwise, B_PORT_BYTE)
        }
    }

    fun runInternalMotors(powerPercentage: Int, timeInMilliseconds: Int, counterclockwise: Boolean) {
        runMotor(powerPercentage, timeInMilliseconds, counterclockwise, AB_PORT_BYTE)
    }

    fun runInternalMotorsInOpposition(powerPercentage: Int, timeInMilliseconds: Int) {
        val timeBytes = getByteArrayFromInt(timeInMilliseconds, 2)
        val motorAPower = powerPercentage.toByte()
        val motorBPower = (255 - powerPercentage).toByte()
        val RUN_MOTOR = byteArrayOf(0x0d, 0x00, 0x81.toByte(), AB_PORT_BYTE, 0x11, 0x0a, timeBytes[0], timeBytes[1], motorAPower, motorBPower, 0x64, 0x7f, 0x03)
        gattController.writeCharacteristic(RUN_MOTOR)
    }

    fun enableNotifications() {
        gattController.setCharacteristicNotification(true)
    }

    fun activateButtonNotifications() {
        gattController.writeCharacteristic(ACTIVATE_BUTTON)
    }

    // Currently not working
    fun deactivateButtonNotifications() {
        gattController.writeCharacteristic(DEACTIVATE_BUTTON)
    }

    fun activateColorSensorNotifications() {
        when (ColorSensorPort) {
            "C" -> gattController.writeCharacteristic(ACTIVATE_COLOR_SENSOR_PORT_C)
            "D" -> gattController.writeCharacteristic(ACTIVATE_COLOR_SENSOR_PORT_D)
        }
    }

    fun deactivateColorSensorNotifications() {
        when (ColorSensorPort) {
            "C" -> gattController.writeCharacteristic(DEACTIVATE_COLOR_SENSOR_PORT_C)
            "D" -> gattController.writeCharacteristic(DEACTIVATE_COLOR_SENSOR_PORT_D)
        }
    }

    fun activateExternalMotorSensorNotifications() {
        when (ExternalMotorPort) {
            "C" -> gattController.writeCharacteristic(ACTIVATE_EXTERNAL_MOTOR_PORT_C)
            "D" -> gattController.writeCharacteristic(ACTIVATE_EXTERNAL_MOTOR_PORT_D)
        }
    }

    fun deactivateExternalMotorSensorNotifications() {
        when (ExternalMotorPort) {
            "C" -> gattController.writeCharacteristic(DEACTIVATE_EXTERNAL_MOTOR_PORT_C)
            "D" -> gattController.writeCharacteristic(DEACTIVATE_EXTERNAL_MOTOR_PORT_D)
        }
    }

    fun activateInternalMotorSensorsNotifications() {
        activateInternalMotorSensorNotifications("A")
        activateInternalMotorSensorNotifications("B")
    }

    fun activateInternalMotorSensorNotifications(motor: String) {
        var data = ACTIVATE_MOTOR_PORT
        when (motor) {
            "A" -> data[3] = A_PORT_BYTE
            "B" -> data[3] = B_PORT_BYTE
        }
        gattController.writeCharacteristic(data)
    }

    fun deactivateInternalMotorSensorsNotifications() {
        deactivateInternalMotorSensorNotifications("A")
        deactivateInternalMotorSensorNotifications("B")
    }

    fun deactivateInternalMotorSensorNotifications(motor: String) {
        var data = DEACTIVATE_MOTOR_PORT
        when (motor) {
            "A" -> data[3] = A_PORT_BYTE
            "B" -> data[3] = B_PORT_BYTE
        }
        gattController.writeCharacteristic(data)
    }

    fun activateTiltSensorNotifications() {
        gattController.writeCharacteristic(ACTIVATE_TILT_SENSOR)
    }

    fun deactivateTiltSensorNotifications() {
        gattController.writeCharacteristic(DEACTIVATE_TILT_SENSOR)
    }

    private fun runMotor(powerPercentage: Int, timeInMilliseconds: Int, counterclockwise: Boolean, portByte: Byte) {
        val powerByte = when (counterclockwise) {
            true -> (255 - powerPercentage).toByte()
            false -> powerPercentage.toByte()
        }
        val timeBytes = getByteArrayFromInt(timeInMilliseconds, 2)
        val RUN_MOTOR = byteArrayOf(0x0c, 0x00, 0x81.toByte(), portByte, 0x11, 0x09, timeBytes[0], timeBytes[1], powerByte, 0x64, 0x7f, 0x03)
        gattController.writeCharacteristic(RUN_MOTOR)
    }

    companion object {
        private val TAG = MoveHubController::class.java.simpleName
    }
}