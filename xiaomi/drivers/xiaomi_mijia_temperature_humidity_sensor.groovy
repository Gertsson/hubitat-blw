/*
 * 
 *  Xiaomi Mijia Temperature and Humidity Sensor Driver for WSDCGQ01LM
 *  Copied from BirdsLikeWires driver for the Xiaomi Aqara Temperature and Humidity Sensor Driver for WSDCGQ11LM
 *	
 */


@Field String driverVersion = "v1.00 (5th May 2023)"


#include BirdsLikeWires.library
#include BirdsLikeWires.xiaomi
import groovy.transform.Field

@Field boolean debugMode = false
@Field int reportIntervalMinutes = 60
@Field int checkEveryMinutes = 10


metadata {

	definition (name: "Xiaomi Mijia Temperature and Humidity Sensor", namespace: "BirdsLikeWires", author: "Andrew Davison", importUrl: "https://github.com/Gertsson/hubitat-blw/blob/master/xiaomi/drivers/xiaomi_mijia_temperature_humidity_sensor.groovy") {

		capability "Battery"
		capability "Configuration"
		capability "PresenceSensor"
		capability "PressureMeasurement"
		capability "PushableButton"
		capability "RelativeHumidityMeasurement"
		capability "Sensor"
		capability "TemperatureMeasurement"
		capability "VoltageMeasurement"

		attribute "absoluteHumidity", "number"
		attribute "pressureDirection", "string"
		//attribute "pressurePrevious", "string"

		if (debugMode) {
			command "checkPresence"
			command "testCommand"
		}

		fingerprint profileId: "0104", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sens", deviceJoinName: "WSDCGQ01LM", endpointId: "02"
		fingerprint profileId: "0104", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_ht", deviceJoinName: "WSDCGQ01LM", endpointId: "02"

	}

}


preferences {
	
	input name: "infoLogging", type: "bool", title: "Enable logging", defaultValue: true
	input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
	input name: "traceLogging", type: "bool", title: "Enable trace logging", defaultValue: false
	input name: "tempDecimals", type: "enum", title: "Temperature decimals", defaultValue: 2, required: true, options: [[0:"None"],[1:"1"],[2:"2 (Default)"]]
	input name: "humidityDecimals", type: "enum", title: "Humidity decimals", defaultValue: 2, required: true, options: [[0:"None"],[1:"1"],[2:"2 (Default)"]]
	
}


void testCommand() {

	logging("${device} : Test Command", "info")
	
}


void configureSpecifics() {
	// Called by main configure() method in BirdsLikeWires.xiaomi

	updateDataValue("encoding", "Xiaomi")
	device.name = "Xiaomi Aqara Temperature and Humidity Sensor WSDCGQ01LM"
	sendEvent(name: "numberOfButtons", value: 1, isStateChange: false)
	if (tempDecimals == null) device.updateSetting("tempDecimals", [value: "2", type: "enum"])
	if (humidityDecimals == null) device.updateSetting("humidityDecimals", [value: "2", type: "enum"])
    
}


void updateSpecifics() {
	// Called by updated() method in BirdsLikeWires.library

	return

}


void processMap(Map map) {

	logging("${device} : processMap() : ${map}", "trace")

	String[] receivedValue = map.value

	if (map.cluster == "0402") { 

		// Received temperature data.

		String[] temperatureHex = receivedValue[2..3] + receivedValue[0..1]
		String temperatureFlippedHex = temperatureHex.join()
		BigDecimal temperature = hexStrToSignedInt(temperatureFlippedHex) / 100
		temperature = temperature.setScale((tempDecimals != null ? tempDecimals : 2).toInteger(), BigDecimal.ROUND_HALF_UP)

		logging("${device} : temperature : ${temperature} from hex value ${temperatureFlippedHex} flipped from ${map.value}", "trace")

		String temperatureScale = location.temperatureScale
		if (temperatureScale == "F") {
			temperature = (temperature * 1.8) + 32
		}

		if (temperature > 200 || temperature < -200) {

			logging("${device} : Temperature : Value of ${temperature}°${temperatureScale} is unusual. Watch out for batteries failing on this device.", "warn")

		} else {

			logging("${device} : Temperature : ${temperature} °${temperatureScale}", "info")
			sendEvent(name: "temperature", value: temperature, unit: "${temperatureScale}")

		}
	} else if (map.cluster == "0405") { 

		// Received humidity data.

		String[] humidityHex = receivedValue[2..3] + receivedValue[0..1]
		String humidityFlippedHex = humidityHex.join()
		BigDecimal humidity = hexStrToSignedInt(humidityFlippedHex) /100
		roundedHumidity = humidity.setScale((humidityDecimals != null ? humidityDecimals : 2).toInteger(), BigDecimal.ROUND_HALF_UP)

		logging("${device} : humidity : ${humidity} from hex value ${humidityFlippedHex} flipped from ${map.value}", "trace")

		BigDecimal lastTemperature = device.currentState("temperature") ? device.currentState("temperature").value.toBigDecimal() : 0

		String temperatureScale = location.temperatureScale
		if (temperatureScale == "F") {
			lastTemperature = (lastTemperature - 32) / 1.8
		}

		BigDecimal numerator = (6.112 * Math.exp((17.67 * lastTemperature) / (lastTemperature + 243.5)) * humidity * 2.1674)
		BigDecimal denominator = lastTemperature + 273.15
		BigDecimal absoluteHumidity = numerator / denominator
		absoluteHumidity = absoluteHumidity.setScale(1, BigDecimal.ROUND_HALF_UP)

		String cubedChar = String.valueOf((char)(179))

		if (humidity > 100 || humidity < 0) {

			logging("${device} : Humidity : Value of ${humidity} is out of bounds. Watch out for batteries failing on this device.", "warn")

		} else {

			logging("${device} : Humidity (Relative) : ${humidity} %", "info")
			logging("${device} : Humidity (Absolute) : ${absoluteHumidity} g/m${cubedChar}", "info")
			sendEvent(name: "humidity", value: roundedHumidity, unit: "%")
			sendEvent(name: "absoluteHumidity", value: absoluteHumidity, unit: "g/m${cubedChar}")

		}

	} else if (map.cluster == "0000") {

		if (map.attrId == "0005") {

			// Scrounge more value! We can capture a short press of the reset button and make it useful.
			logging("${device} : Trigger : Button Pressed", "info")
			sendEvent(name: "pushed", value: 1, isStateChange: true)

		} else {

			// processBasic(map)
			filterThis(map)

		}

	} else {

		filterThis(map)

	}

}

