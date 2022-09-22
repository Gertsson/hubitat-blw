/*
 * 
 *  AlertMe Smart Plug Driver v1.45 (22nd September 2022)
 *	
 */


#include BirdsLikeWires.alertme
#include BirdsLikeWires.library
import groovy.transform.Field

@Field boolean debugMode = false
@Field int reportIntervalMinutes = 2
@Field int checkEveryMinutes = 1
@Field int rangeEveryHours = 6


metadata {

	definition (name: "AlertMe Smart Plug", namespace: "BirdsLikeWires", author: "Andrew Davison", importUrl: "https://raw.githubusercontent.com/birdslikewires/hubitat/master/alertme/drivers/alertme_smartplug.groovy") {

		capability "Actuator"
		capability "Battery"
		capability "Configuration"
		capability "EnergyMeter"
		capability "Outlet"
		capability "PowerMeter"
		capability "PowerSource"
		capability "PresenceSensor"
		capability "Refresh"
		capability "SignalStrength"
		capability "Switch"
		capability "TamperAlert"
		capability "TemperatureMeasurement"

		//command "lockedMode"
		command "normalMode"
		command "rangingMode"
		//command "quietMode"

		attribute "batteryState", "string"
		attribute "batteryVoltage", "string"
		attribute "mode", "string"
		attribute "stateMismatch", "boolean"
		attribute "uptime", "string"
		attribute "uptimeReadable", "string"

		if (debugMode) {
			command "checkPresence"
			command "testCommand"
		}

		fingerprint profileId: "C216", inClusters: "00F0,00F3,00F1,00EF,00EE", outClusters: "", manufacturer: "AlertMe.com", model: "Smart Plug", deviceJoinName: "AlertMe Smart Plug"
		
	}

}


preferences {
	
	input name: "infoLogging", type: "bool", title: "Enable logging", defaultValue: true
	input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
	input name: "traceLogging", type: "bool", title: "Enable trace logging", defaultValue: false
	
}


void testCommand() {

	logging("${device} : Test Command", "info")
	sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 ${device.endpointId} 0x00F6 {11 00 FC 01} {0xC216}"])	   // version information request

}


void configureSpecifics() {
	// Called by main configure() method in BirdsLikeWires.alertme

	// Set device name.
	device.name = "AlertMe Smart Plug"

	// Enable power control.
	sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 ${device.endpointId} 0x00EE {11 00 01 01} {0xC216}"])

}


void off() {

	// The off command is custom to AlertMe equipment, so has to be constructed.
	sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 ${device.endpointId} 0x00EE {11 00 02 00 01} {0xC216}"])

}


void on() {

	// The on command is custom to AlertMe equipment, so has to be constructed.
	sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 ${device.endpointId} 0x00EE {11 00 02 01 01} {0xC216}"])

}


void processMap(Map map) {

	logging("${device} : processMap() : ${map}", "trace")

	if (map.clusterId == "0006") {

		// Match Descriptor Request
		logging("${device} : Sending Match Descriptor Response", "debug")
		sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 ${device.endpointId} 0x8006 {00 00 00 01 02} {0xC216}"])

	} else if (map.clusterId == "00EE") {

		// Relay actuation and power state messages.

		if (map.command == "80") {

			// Power States

			def powerStateHex = "undefined"
			powerStateHex = map.data[0]

			// Power states are fun.
			//   00 00 - Cold mains power on with relay off (only occurs when battery dead or after reset)
			//   01 01 - Cold mains power on with relay on (only occurs when battery dead or after reset)
			//   02 00 - Mains power off and relay off [BATTERY OPERATION]
			//   03 01 - Mains power off and relay on [BATTERY OPERATION]
			//   04 00 - Mains power returns with relay off (only follows a 00 00)
			//   05 01 - Mains power returns with relay on (only follows a 01 01)
			//   06 00 - Mains power on and relay off (normal actuation)
			//   07 01 - Mains power on and relay on (normal actuation)

			if (powerStateHex == "02" || powerStateHex == "03") {

				// Supply failed.

				sendEvent(name: "batteryState", value: "discharging")
				sendEvent(name: "powerSource", value: "battery")
				sendEvent(name: "tamper", value: "detected")
				state.supplyPresent = false

				// Whether this is a problem!

				if (powerStateHex == "02") {

					logging("${device} : Supply : Incoming supply failure with relay open.", "warn")
					sendEvent(name: "stateMismatch", value: false, isStateChange: true)

				} else {

					logging("${device} : Supply : Incoming supply failure with relay closed. CANNOT POWER LOAD!", "warn")
					sendEvent(name: "stateMismatch", value: true, isStateChange: true)

				}

			} else if (powerStateHex == "06" || powerStateHex == "07") {

				// Supply present.

				state.supplyPresent ?: logging("${device} : Supply : Incoming supply has returned.", "info")
				state.supplyPresent ?: sendEvent(name: "batteryState", value: "charging")

				sendEvent(name: "stateMismatch", value: false)
				sendEvent(name: "powerSource", value: "mains")
				sendEvent(name: "tamper", value: "clear")
				state.supplyPresent = true

			} else {

				// Supply returned!

				logging("${device} : Supply : Device returning from shutdown, please check batteries!", "warn")

				if (state.batteryOkay) {
					sendEvent(name: "batteryState", value: "charging")
				}

				sendEvent(name: "stateMismatch", value: false)
				sendEvent(name: "powerSource", value: "mains")
				sendEvent(name: "tamper", value: "clear")
				state.supplyPresent = true

			}

			// Relay States

			def switchStateHex = "undefined"
			switchStateHex = map.data[1]

			if (switchStateHex == "01") {

				state.relayClosed = true
				sendEvent(name: "switch", value: "on")
				logging("${device} : Switch : On", "info")

			} else {

				state.relayClosed = false
				sendEvent(name: "switch", value: "off")
				logging("${device} : Switch : Off", "info")

			}

		} else {

			reportToDev(map)

		}

	} else if (map.clusterId == "00EF") {

		// Power and energy messages.

		if (map.command == "81") {

			// Power Reading

			def powerValueHex = "undefined"
			int powerValue = 0

			// These power readings are so frequent that we only log them in debug or trace.
			powerValueHex = map.data[0..1].reverse().join()
			logging("${device} : power byte flipped : ${powerValueHex}", "trace")
			powerValue = zigbee.convertHexToInt(powerValueHex)
			logging("${device} : power sensor reports : ${powerValue}", "debug")

			sendEvent(name: "power", value: powerValue, unit: "W")

		} else if (map.command == "82") {

			// Command 82 returns energy summary in watt-hours with an uptime counter.

			// Energy

			String energyValueHex = "undefined"
			energyValueHex = map.data[0..3].reverse().join()
			logging("${device} : energy byte flipped : ${energyValueHex}", "trace")

			BigInteger energyValue = new BigInteger(energyValueHex, 16)
			logging("${device} : energy counter reports : ${energyValue}", "debug")

			BigDecimal energyValueDecimal = BigDecimal.valueOf(energyValue / 3600 / 1000)
			energyValueDecimal = energyValueDecimal.setScale(4, BigDecimal.ROUND_HALF_UP)

			logging("${device} : Energy : ${energyValueDecimal} kWh", "info")

			sendEvent(name: "energy", value: energyValueDecimal, unit: "kWh")

			// Uptime

			String uptimeValueHex = "undefined"
			uptimeValueHex = map.data[4..8].reverse().join()
			logging("${device} : uptime byte flipped : ${uptimeValueHex}", "trace")

			BigInteger uptimeValue = new BigInteger(uptimeValueHex, 16)
			logging("${device} : uptime counter reports : ${uptimeValue}", "debug")

			def newDhmsUptime = []
			long totalUptimeValue = uptimeValue * 1000
			newDhmsUptime = millisToDhms(totalUptimeValue)
			String uptimeReadable = "${newDhmsUptime[3]}d ${newDhmsUptime[2]}h ${newDhmsUptime[1]}m"

			logging("${device} : Uptime : ${uptimeReadable}", "debug")

			sendEvent(name: "uptime", value: uptimeValue, unit: "s")
			sendEvent(name: "uptimeReadable", value: uptimeReadable)

		} else {

			// Unknown power or energy data.
			reportToDev(map)

		}

	} else if (map.clusterId == "00F0") {

		// Device status cluster.
		alertmeDeviceStatus(map)

	} else if (map.clusterId == "00F6") {

		// 00F6 - Discovery Cluster
		alertmeDiscovery(map)

	} else if (map.clusterId == "8001" || map.clusterId == "8032" || map.clusterId == "8038") {

		alertmeSkip(map.clusterId)

	} else {

		// Not a clue what we've received.
		reportToDev(map)

	}

}
