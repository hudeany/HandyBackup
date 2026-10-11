package de.soderer.handybackup.mtp;

import java.time.LocalDateTime;

/**
 * File or folder on an MTP device.
 *
 * @param path      absolute path on the device, e.g. "/Interner gemeinsamer Speicher/DCIM/Camera/IMG_0001.jpg"
 * @param name      file or folder name (including extension)
 * @param directory true for folders and storages
 * @param size      file size in bytes, -1 for folders or if unknown
 * @param modified  last modification date as reported by the device, null if unknown
 * @param objectId  WPD object id of this file or folder on the device
 */
public record MtpFile(String path, String name, boolean directory, long size, LocalDateTime modified, String objectId) {
}
