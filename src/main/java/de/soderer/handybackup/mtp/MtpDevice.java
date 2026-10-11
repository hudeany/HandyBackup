package de.soderer.handybackup.mtp;

/**
 * Connected MTP device, as returned by {@link MtpManager#getConnectedDevices()}.
 *
 * @param name         display name as shown in Explorer (e.g. "Nokia 7.1")
 * @param manufacturer manufacturer reported by the device (e.g. "HMD Global"), null if not provided
 * @param model        model name reported by the device (e.g. "Nokia 7.1"), null if not provided
 * @param serialNumber serial number as shown in Explorer's device properties, null if it could not be read
 * @param powerLevel   battery level in percent, null if not provided
 * @param protocol     transport protocol reported by the device (e.g. "MTP: 1.00"), null if not provided
 * @param pnpDeviceId  WPD/PnP device interface path, used to open the device
 * @param errorMessage reason why the device properties could not be read, null on success
 */
public record MtpDevice(String name, String manufacturer, String model, String serialNumber, Integer powerLevel, String protocol, String pnpDeviceId, String errorMessage) {
}
