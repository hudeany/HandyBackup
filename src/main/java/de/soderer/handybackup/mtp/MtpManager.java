package de.soderer.handybackup.mtp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Access to MTP devices (e.g. Android phones in "File transfer" USB mode) on Windows.
 *
 * Uses the Windows Portable Devices (WPD) COM API through a helper PowerShell process. The process
 * compiles a C# interop definition once at startup (about 1-2 seconds) and then executes commands
 * sent via stdin, so all further calls are fast. The helper process lives as long as this object is
 * open, so close it after use (try-with-resources). No external libraries are required.
 *
 * All commands are executed one after another in the helper process. To work on several devices
 * in parallel, use one MtpManager per device.
 *
 * Example:
 * <pre>
 * try (MtpManager manager = new MtpManager()) {
 *     for (MtpDevice device : manager.getConnectedDevices()) {
 *         try (MtpFileSystem fileSystem = manager.open(device)) {
 *             fileSystem.list("/");
 *         }
 *     }
 * }
 * </pre>
 */
public class MtpManager implements AutoCloseable {
	/** COM interop and command loop of the helper process (vtable order must match PortableDeviceApi.h) */
	private static final String WPD_SERVER_SOURCE = """
		using System;
		using System.Collections.Generic;
		using System.Globalization;
		using System.IO;
		using System.Runtime.InteropServices;
		using System.Text;
		using IStream = System.Runtime.InteropServices.ComTypes.IStream;

		namespace Soderer.Wpd {
			[StructLayout(LayoutKind.Sequential)]
			public struct PropertyKey {
				public Guid fmtid;
				public uint pid;
				public PropertyKey(Guid fmtid, uint pid) { this.fmtid = fmtid; this.pid = pid; }
			}

			[ComImport, Guid("a1567595-4c2f-4574-a6fa-ecef917b9a40"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			public interface IPortableDeviceManager {
				void GetDevices(IntPtr pnpDeviceIds, ref uint count);
				void RefreshDeviceList();
				void GetDeviceFriendlyName([MarshalAs(UnmanagedType.LPWStr)] string pnpDeviceId, IntPtr friendlyName, ref uint length);
			}

			[ComImport, Guid("6848f6f2-3155-4f86-b6f5-263eeeab3143"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			public interface IPortableDeviceValues {
				void GetCount(ref uint count);
				void GetAt(uint index, ref PropertyKey key, IntPtr value);
				void SetValue(ref PropertyKey key, IntPtr value);
				void GetValue(ref PropertyKey key, IntPtr value);
				void SetStringValue(ref PropertyKey key, [MarshalAs(UnmanagedType.LPWStr)] string value);
				void GetStringValue(ref PropertyKey key, [MarshalAs(UnmanagedType.LPWStr)] out string value);
				void SetUnsignedIntegerValue(ref PropertyKey key, uint value);
				void GetUnsignedIntegerValue(ref PropertyKey key, out uint value);
				void SetSignedIntegerValue(ref PropertyKey key, int value);
				void GetSignedIntegerValue(ref PropertyKey key, out int value);
				void SetUnsignedLargeIntegerValue(ref PropertyKey key, ulong value);
				void GetUnsignedLargeIntegerValue(ref PropertyKey key, out ulong value);
				void SetSignedLargeIntegerValue(ref PropertyKey key, long value);
				void GetSignedLargeIntegerValue(ref PropertyKey key, out long value);
				void SetFloatValue(ref PropertyKey key, float value);
				void GetFloatValue(ref PropertyKey key, out float value);
				void SetErrorValue(ref PropertyKey key, int value);
				void GetErrorValue(ref PropertyKey key, out int value);
				void SetKeyValue(ref PropertyKey key, ref PropertyKey value);
				void GetKeyValue(ref PropertyKey key, out PropertyKey value);
				void SetBoolValue(ref PropertyKey key, int value);
				void GetBoolValue(ref PropertyKey key, out int value);
				void SetIUnknownValue(ref PropertyKey key, IntPtr value);
				void GetIUnknownValue(ref PropertyKey key, out IntPtr value);
				void SetGuidValue(ref PropertyKey key, ref Guid value);
				void GetGuidValue(ref PropertyKey key, out Guid value);
			}

			[ComImport, Guid("dada2357-e0ad-492e-98db-dd61c53ba353"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			public interface IPortableDeviceKeyCollection {
				void GetCount(ref uint count);
				void GetAt(uint index, ref PropertyKey key);
				void Add(ref PropertyKey key);
				void Clear();
				void RemoveAt(uint index);
			}

			[ComImport, Guid("89b2e422-4f1b-4316-bcef-a44afea83eb3"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			public interface IPortableDevicePropVariantCollection {
				void GetCount(ref uint count);
				void GetAt(uint index, IntPtr value);
				void Add(IntPtr value);
				void GetVarType(out ushort varType);
				void ChangeType(ushort varType);
				void Clear();
				void RemoveAt(uint index);
			}

			[ComImport, Guid("10ece955-cf41-4728-bfa0-41eedf1bbf19"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			public interface IEnumPortableDeviceObjectIDs {
				[PreserveSig]
				int Next(uint count, [MarshalAs(UnmanagedType.LPWStr)] out string objectId, ref uint fetched);
			}

			[ComImport, Guid("7f6d695c-03df-4439-a809-59266beee3a6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			public interface IPortableDeviceProperties {
				void GetSupportedProperties([MarshalAs(UnmanagedType.LPWStr)] string objectId, out IPortableDeviceKeyCollection keys);
				void GetPropertyAttributes([MarshalAs(UnmanagedType.LPWStr)] string objectId, ref PropertyKey key, out IPortableDeviceValues attributes);
				void GetValues([MarshalAs(UnmanagedType.LPWStr)] string objectId, IPortableDeviceKeyCollection keys, out IPortableDeviceValues values);
			}

			[ComImport, Guid("fd8878ac-d841-4d17-891c-e6829cdb6934"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			public interface IPortableDeviceResources {
				void GetSupportedResources([MarshalAs(UnmanagedType.LPWStr)] string objectId, out IPortableDeviceKeyCollection keys);
				void GetResourceAttributes([MarshalAs(UnmanagedType.LPWStr)] string objectId, ref PropertyKey key, out IPortableDeviceValues attributes);
				void GetStream([MarshalAs(UnmanagedType.LPWStr)] string objectId, ref PropertyKey key, uint mode, ref uint optimalBufferSize, out IStream stream);
			}

			[ComImport, Guid("6a96ed84-7c73-4480-9938-bf5af477d426"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			public interface IPortableDeviceContent {
				void EnumObjects(uint flags, [MarshalAs(UnmanagedType.LPWStr)] string parentObjectId, IPortableDeviceValues filter, out IEnumPortableDeviceObjectIDs enumObjectIds);
				void Properties(out IPortableDeviceProperties properties);
				void Transfer(out IPortableDeviceResources resources);
				void CreateObjectWithPropertiesOnly(IPortableDeviceValues values, [MarshalAs(UnmanagedType.LPWStr)] out string objectId);
				void CreateObjectWithPropertiesAndData(IPortableDeviceValues values, out IStream data, ref uint optimalWriteBufferSize, IntPtr cookie);
				void Delete(uint options, IPortableDevicePropVariantCollection objectIds, IntPtr results);
			}

			[ComImport, Guid("625e2df8-6392-4cf0-9ad1-3cfa5f17775c"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
			public interface IPortableDevice {
				void Open([MarshalAs(UnmanagedType.LPWStr)] string pnpDeviceId, IPortableDeviceValues clientInfo);
				void SendCommand(uint flags, IPortableDeviceValues parameters, out IPortableDeviceValues results);
				void Content(out IPortableDeviceContent content);
				void Capabilities(out IntPtr capabilities);
				void Cancel();
				void Close();
			}

			/// <summary>WPD constants and value helpers</summary>
			public static class Wpd {
				[DllImport("ole32.dll")]
				public static extern int PropVariantClear(IntPtr propVariant);

				public static readonly Guid CLSID_PortableDeviceManager = new Guid("0af10cec-2ecd-4b92-9581-34f6ae0637f3");
				public static readonly Guid CLSID_PortableDevice = new Guid("728a21c5-3d9e-48d7-9810-864848f0f404");
				public static readonly Guid CLSID_PortableDeviceValues = new Guid("0c15d503-d017-47ce-9016-7b3f978721cc");
				public static readonly Guid CLSID_PortableDeviceKeyCollection = new Guid("de2d022d-2480-43be-97f0-d1fa2cf98f4f");
				public static readonly Guid CLSID_PortableDevicePropVariantCollection = new Guid("08a99e2f-6d6d-4b80-af5a-baf2bcbe4cb9");

				// WPD_DEVICE_PROPERTIES_V1
				public static readonly Guid DeviceProperties = new Guid("26d4979a-e643-4626-9e2b-736dc0c92fdc");
				public static readonly PropertyKey DevicePowerLevel = new PropertyKey(DeviceProperties, 4);
				public static readonly PropertyKey DeviceProtocol = new PropertyKey(DeviceProperties, 6);
				public static readonly PropertyKey DeviceManufacturer = new PropertyKey(DeviceProperties, 7);
				public static readonly PropertyKey DeviceModel = new PropertyKey(DeviceProperties, 8);
				public static readonly PropertyKey DeviceSerialNumber = new PropertyKey(DeviceProperties, 9);
				public static readonly PropertyKey DeviceFriendlyName = new PropertyKey(DeviceProperties, 12);

				// WPD_OBJECT_PROPERTIES_V1
				public static readonly Guid ObjectProperties = new Guid("ef6b490d-5cd8-437a-affc-da8b60ee4a3c");
				public static readonly PropertyKey ObjectParentId = new PropertyKey(ObjectProperties, 3);
				public static readonly PropertyKey ObjectName = new PropertyKey(ObjectProperties, 4);
				public static readonly PropertyKey ObjectFormat = new PropertyKey(ObjectProperties, 6);
				public static readonly PropertyKey ObjectContentType = new PropertyKey(ObjectProperties, 7);
				public static readonly PropertyKey ObjectSize = new PropertyKey(ObjectProperties, 11);
				public static readonly PropertyKey ObjectOriginalFileName = new PropertyKey(ObjectProperties, 12);
				public static readonly PropertyKey ObjectDateModified = new PropertyKey(ObjectProperties, 19);

				// WPD_RESOURCE_DEFAULT
				public static readonly PropertyKey ResourceDefault = new PropertyKey(new Guid("e81e79be-34f0-41bf-b53f-f1a06ae87842"), 0);

				public static readonly Guid ContentTypeFolder = new Guid("27e2e392-a111-48e0-ab0c-e17705a05f85");
				public static readonly Guid ContentTypeFunctionalObject = new Guid("99ed0160-17ff-4c44-9d98-1d7a6f941921");
				public static readonly Guid ContentTypeGenericFile = new Guid("0085e0a6-8d34-45d7-bc5c-447e59c73d48");
				public static readonly Guid FormatUnspecified = new Guid("30000000-ae6c-4804-98ba-c57b46965fe7");
				public static readonly Guid FormatPropertiesOnly = new Guid("30010000-ae6c-4804-98ba-c57b46965fe7");

				public const string DeviceObjectId = "DEVICE";
				private const ushort VT_DATE = 7;
				public const ushort VT_LPWSTR = 31;
				private const ushort VT_FILETIME = 64;
				private const int PropVariantSize = 24;

				public static object CreateComObject(Guid clsid) {
					return Activator.CreateInstance(Type.GetTypeFromCLSID(clsid, true));
				}

				public static IPortableDevice OpenDevice(string pnpDeviceId) {
					IPortableDevice device = (IPortableDevice) CreateComObject(CLSID_PortableDevice);
					device.Open(pnpDeviceId, (IPortableDeviceValues) CreateComObject(CLSID_PortableDeviceValues));
					return device;
				}

				public static string GetString(IPortableDeviceValues values, PropertyKey key) {
					try {
						string value;
						values.GetStringValue(ref key, out value);
						return value;
					} catch (COMException) {
						// Property not provided by the device
						return null;
					}
				}

				public static uint? GetUInt(IPortableDeviceValues values, PropertyKey key) {
					try {
						uint value;
						values.GetUnsignedIntegerValue(ref key, out value);
						return value;
					} catch (COMException) {
						// Property not provided by the device
						return null;
					}
				}

				public static long GetLong(IPortableDeviceValues values, PropertyKey key) {
					try {
						ulong value;
						values.GetUnsignedLargeIntegerValue(ref key, out value);
						return (long) value;
					} catch (COMException) {
						// Property not provided by the device
						return -1;
					}
				}

				public static bool TryGetGuid(IPortableDeviceValues values, PropertyKey key, out Guid value) {
					try {
						values.GetGuidValue(ref key, out value);
						return true;
					} catch (COMException) {
						// Property not provided by the device
						value = Guid.Empty;
						return false;
					}
				}

				public static DateTime? GetDate(IPortableDeviceValues values, PropertyKey key) {
					IntPtr propVariant = AllocPropVariant();
					try {
						values.GetValue(ref key, propVariant);
						ushort varType = (ushort) Marshal.ReadInt16(propVariant, 0);
						if (varType == VT_DATE) {
							return DateTime.FromOADate(BitConverter.Int64BitsToDouble(Marshal.ReadInt64(propVariant, 8)));
						} else if (varType == VT_FILETIME) {
							return DateTime.FromFileTime(Marshal.ReadInt64(propVariant, 8));
						} else {
							return null;
						}
					} catch (COMException) {
						// Property not provided by the device
						return null;
					} catch (ArgumentException) {
						// Invalid date value
						return null;
					} finally {
						PropVariantClear(propVariant);
						Marshal.FreeHGlobal(propVariant);
					}
				}

				public static void SetString(IPortableDeviceValues values, PropertyKey key, string value) {
					values.SetStringValue(ref key, value);
				}

				public static void SetGuid(IPortableDeviceValues values, PropertyKey key, Guid value) {
					values.SetGuidValue(ref key, ref value);
				}

				public static IntPtr AllocPropVariant() {
					// PROPVARIANT is 16 bytes on 32 bit and 24 bytes on 64 bit, allocate the larger size and zero it
					IntPtr propVariant = Marshal.AllocHGlobal(PropVariantSize);
					for (int offset = 0; offset < PropVariantSize; offset += 8) {
						Marshal.WriteInt64(propVariant, offset, 0);
					}
					return propVariant;
				}
			}

			public class MtpItem {
				public string Id;
				public string Name;
				public bool IsDirectory;
				public long Size = -1;
				public DateTime? Modified;
			}

			/// <summary>An opened device with its file system operations</summary>
			public class DeviceSession {
				private readonly IPortableDevice device;
				private readonly IPortableDeviceContent content;
				private readonly IPortableDeviceProperties properties;
				private readonly IPortableDeviceKeyCollection itemKeys;
				private readonly Dictionary<string, string> objectIdCache = new Dictionary<string, string>();

				public DeviceSession(string pnpDeviceId) {
					device = Wpd.OpenDevice(pnpDeviceId);
					device.Content(out content);
					content.Properties(out properties);

					// Only request the properties needed for listings, reading all properties is slow over MTP
					itemKeys = (IPortableDeviceKeyCollection) Wpd.CreateComObject(Wpd.CLSID_PortableDeviceKeyCollection);
					PropertyKey[] keys = { Wpd.ObjectName, Wpd.ObjectOriginalFileName, Wpd.ObjectContentType, Wpd.ObjectSize, Wpd.ObjectDateModified };
					for (int i = 0; i < keys.Length; i++) {
						PropertyKey key = keys[i];
						itemKeys.Add(ref key);
					}
					ClearCache();
				}

				public void Close() {
					try {
						device.Close();
					} catch (Exception) {
						// Ignore errors on close
					}
				}

				public void List(string path) {
					MtpItem item = GetItemByPath(path);
					if (item == null) {
						throw new FileNotFoundException("Not found on device: " + path);
					} else if (!item.IsDirectory) {
						throw new IOException("Not a folder: " + path);
					}
					foreach (MtpItem child in ListChildren(item.Id, path)) {
						MtpServer.WriteItem(child, CombinePath(path, child.Name));
					}
				}

				public void Stat(string path) {
					MtpItem item = GetItemByPath(path);
					if (item != null) {
						MtpServer.WriteItem(item, path);
					}
				}

				public void Download(string path, string localFilePath) {
					MtpItem item = GetItemByPath(path);
					if (item == null) {
						throw new FileNotFoundException("Not found on device: " + path);
					} else if (item.IsDirectory) {
						throw new IOException("Cannot download a folder: " + path);
					}

					IPortableDeviceResources resources;
					content.Transfer(out resources);
					IStream stream;
					uint optimalBufferSize = 0;
					PropertyKey key = Wpd.ResourceDefault;
					// 0 = STGM_READ
					resources.GetStream(item.Id, ref key, 0, ref optimalBufferSize, out stream);

					byte[] buffer = new byte[Math.Max(optimalBufferSize, 256 * 1024)];
					IntPtr bytesReadPointer = Marshal.AllocHGlobal(sizeof(int));
					bool success = false;
					try {
						using (FileStream fileStream = new FileStream(localFilePath, FileMode.Create, FileAccess.Write)) {
							while (true) {
								stream.Read(buffer, buffer.Length, bytesReadPointer);
								int bytesRead = Marshal.ReadInt32(bytesReadPointer);
								if (bytesRead <= 0) {
									break;
								}
								fileStream.Write(buffer, 0, bytesRead);
							}
						}
						if (item.Modified.HasValue) {
							File.SetLastWriteTime(localFilePath, item.Modified.Value);
						}
						success = true;
					} finally {
						Marshal.FreeHGlobal(bytesReadPointer);
						Marshal.ReleaseComObject(stream);
						Marshal.ReleaseComObject(resources);
						if (!success) {
							try {
								File.Delete(localFilePath);
							} catch (Exception) {
								// Keep the original exception
							}
						}
					}
				}

				public void Upload(string localFilePath, string targetFolderPath) {
					FileInfo file = new FileInfo(localFilePath);
					if (!file.Exists) {
						throw new FileNotFoundException("Local file not found: " + localFilePath);
					}
					MtpItem folder = GetItemByPath(targetFolderPath);
					if (folder == null) {
						throw new FileNotFoundException("Not found on device: " + targetFolderPath);
					} else if (!folder.IsDirectory || folder.Id == Wpd.DeviceObjectId) {
						throw new IOException("Not a folder: " + targetFolderPath);
					}
					// MTP allows several objects with the same name in one folder, so check explicitly
					foreach (MtpItem child in ListChildren(folder.Id, targetFolderPath)) {
						if (child.Name == file.Name) {
							throw new IOException("Already exists on device: " + CombinePath(targetFolderPath, file.Name));
						}
					}

					IPortableDeviceValues values = (IPortableDeviceValues) Wpd.CreateComObject(Wpd.CLSID_PortableDeviceValues);
					Wpd.SetString(values, Wpd.ObjectParentId, folder.Id);
					Wpd.SetString(values, Wpd.ObjectName, file.Name);
					Wpd.SetString(values, Wpd.ObjectOriginalFileName, file.Name);
					PropertyKey sizeKey = Wpd.ObjectSize;
					values.SetUnsignedLargeIntegerValue(ref sizeKey, (ulong) file.Length);
					Wpd.SetGuid(values, Wpd.ObjectContentType, Wpd.ContentTypeGenericFile);
					Wpd.SetGuid(values, Wpd.ObjectFormat, Wpd.FormatUnspecified);

					IStream data;
					uint optimalBufferSize = 0;
					content.CreateObjectWithPropertiesAndData(values, out data, ref optimalBufferSize, IntPtr.Zero);
					try {
						byte[] buffer = new byte[Math.Max(optimalBufferSize, 256 * 1024)];
						using (FileStream fileStream = new FileStream(localFilePath, FileMode.Open, FileAccess.Read)) {
							int bytesRead;
							while ((bytesRead = fileStream.Read(buffer, 0, buffer.Length)) > 0) {
								data.Write(buffer, bytesRead, IntPtr.Zero);
							}
						}
						// The object is only created on the device when the stream is committed (0 = STGC_DEFAULT)
						data.Commit(0);
					} finally {
						Marshal.ReleaseComObject(data);
						Marshal.ReleaseComObject(values);
						ClearCache();
					}

					string path = CombinePath(targetFolderPath, file.Name);
					MtpItem created = GetItemByPath(path);
					if (created == null) {
						throw new IOException("Upload finished, but file not found on device: " + path);
					}
					MtpServer.WriteItem(created, path);
				}

				public void Delete(string path, bool recursive) {
					if (path.LastIndexOf('/') == 0) {
						throw new IOException("Cannot delete the device or a storage: " + path);
					}
					MtpItem item = GetItemByPath(path);
					if (item == null) {
						throw new FileNotFoundException("Not found on device: " + path);
					}
					try {
						DeleteItem(item, path, recursive);
					} finally {
						ClearCache();
					}
				}

				private void DeleteItem(MtpItem item, string path, bool recursive) {
					if (item.IsDirectory && recursive) {
						// Delete contents first, not all devices support PORTABLE_DEVICE_DELETE_WITH_RECURSION
						foreach (MtpItem child in ListChildren(item.Id, path)) {
							DeleteItem(child, CombinePath(path, child.Name), true);
						}
					}

					IPortableDevicePropVariantCollection objectIds = (IPortableDevicePropVariantCollection) Wpd.CreateComObject(Wpd.CLSID_PortableDevicePropVariantCollection);
					IntPtr propVariant = Wpd.AllocPropVariant();
					try {
						Marshal.WriteInt16(propVariant, 0, (short) Wpd.VT_LPWSTR);
						Marshal.WriteIntPtr(propVariant, 8, Marshal.StringToCoTaskMemUni(item.Id));
						objectIds.Add(propVariant);
					} finally {
						Wpd.PropVariantClear(propVariant);
						Marshal.FreeHGlobal(propVariant);
					}
					try {
						// 0 = PORTABLE_DEVICE_DELETE_NO_RECURSION
						content.Delete(0, objectIds, IntPtr.Zero);
					} finally {
						Marshal.ReleaseComObject(objectIds);
					}
				}

				public void CreateDirectory(string path) {
					int separatorIndex = path.LastIndexOf('/');
					if (separatorIndex <= 0) {
						throw new IOException("Cannot create a storage: " + path);
					}
					if (GetItemByPath(path) != null) {
						throw new IOException("Already exists on device: " + path);
					}
					string parentPath = path.Substring(0, separatorIndex);
					string name = path.Substring(separatorIndex + 1);
					MtpItem parent = GetItemByPath(parentPath);
					if (parent == null) {
						throw new FileNotFoundException("Not found on device: " + parentPath);
					} else if (!parent.IsDirectory) {
						throw new IOException("Not a folder: " + parentPath);
					}

					IPortableDeviceValues values = (IPortableDeviceValues) Wpd.CreateComObject(Wpd.CLSID_PortableDeviceValues);
					Wpd.SetString(values, Wpd.ObjectParentId, parent.Id);
					Wpd.SetString(values, Wpd.ObjectName, name);
					Wpd.SetString(values, Wpd.ObjectOriginalFileName, name);
					Wpd.SetGuid(values, Wpd.ObjectContentType, Wpd.ContentTypeFolder);
					Wpd.SetGuid(values, Wpd.ObjectFormat, Wpd.FormatPropertiesOnly);
					string objectId;
					try {
						content.CreateObjectWithPropertiesOnly(values, out objectId);
					} finally {
						Marshal.ReleaseComObject(values);
						ClearCache();
					}
					MtpServer.WriteItem(GetItem(objectId), path);
				}

				private MtpItem GetItemByPath(string path) {
					if (path == "/") {
						MtpItem root = new MtpItem();
						root.Id = Wpd.DeviceObjectId;
						root.Name = "";
						root.IsDirectory = true;
						return root;
					}
					string objectId = ResolveObjectId(path);
					return objectId == null ? null : GetItem(objectId);
				}

				private string ResolveObjectId(string path) {
					string objectId;
					if (objectIdCache.TryGetValue(path, out objectId)) {
						return objectId;
					}
					int separatorIndex = path.LastIndexOf('/');
					string parentPath = separatorIndex == 0 ? "/" : path.Substring(0, separatorIndex);
					string parentObjectId = ResolveObjectId(parentPath);
					if (parentObjectId == null) {
						return null;
					}
					// Listing the parent fills the cache for all of its children
					ListChildren(parentObjectId, parentPath);
					return objectIdCache.TryGetValue(path, out objectId) ? objectId : null;
				}

				private List<MtpItem> ListChildren(string parentObjectId, string parentPath) {
					List<MtpItem> result = new List<MtpItem>();
					IEnumPortableDeviceObjectIDs enumerator;
					content.EnumObjects(0, parentObjectId, null, out enumerator);
					try {
						while (true) {
							string objectId;
							uint fetched = 0;
							int hresult = enumerator.Next(1, out objectId, ref fetched);
							if (hresult < 0) {
								Marshal.ThrowExceptionForHR(hresult);
							}
							if (fetched == 0 || objectId == null) {
								break;
							}
							MtpItem item = GetItem(objectId);
							result.Add(item);
							string childPath = CombinePath(parentPath, item.Name);
							if (!objectIdCache.ContainsKey(childPath)) {
								objectIdCache[childPath] = objectId;
							}
						}
					} finally {
						Marshal.ReleaseComObject(enumerator);
					}
					return result;
				}

				private MtpItem GetItem(string objectId) {
					IPortableDeviceValues values;
					properties.GetValues(objectId, itemKeys, out values);
					try {
						MtpItem item = new MtpItem();
						item.Id = objectId;
						// Object names of files may lack the extension, the original file name does not
						item.Name = Wpd.GetString(values, Wpd.ObjectOriginalFileName);
						if (string.IsNullOrEmpty(item.Name)) {
							item.Name = Wpd.GetString(values, Wpd.ObjectName);
						}
						if (string.IsNullOrEmpty(item.Name)) {
							item.Name = objectId;
						}
						Guid contentType;
						if (Wpd.TryGetGuid(values, Wpd.ObjectContentType, out contentType)) {
							item.IsDirectory = contentType == Wpd.ContentTypeFolder || contentType == Wpd.ContentTypeFunctionalObject;
						}
						if (!item.IsDirectory) {
							item.Size = Wpd.GetLong(values, Wpd.ObjectSize);
						}
						item.Modified = Wpd.GetDate(values, Wpd.ObjectDateModified);
						return item;
					} finally {
						Marshal.ReleaseComObject(values);
					}
				}

				private void ClearCache() {
					objectIdCache.Clear();
					objectIdCache["/"] = Wpd.DeviceObjectId;
				}

				private static string CombinePath(string parentPath, string name) {
					return parentPath == "/" ? "/" + name : parentPath + "/" + name;
				}
			}

			/// <summary>Command loop reading commands from stdin and writing results to stdout</summary>
			public static class MtpServer {
				private const string DateFormat = "yyyy-MM-dd'T'HH:mm:ss";

				private static readonly Dictionary<string, DeviceSession> sessions = new Dictionary<string, DeviceSession>();
				private static int nextHandle = 1;
				private static TextWriter output;

				public static void Run() {
					TextReader input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
					StreamWriter writer = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false));
					writer.AutoFlush = true;
					writer.NewLine = "\\n";
					output = writer;
					output.WriteLine("READY");

					string line;
					while ((line = input.ReadLine()) != null) {
						string[] parts = line.Split('\\t');
						if (parts[0] == "EXIT") {
							break;
						}
						string[] arguments = new string[parts.Length - 1];
						for (int i = 1; i < parts.Length; i++) {
							arguments[i - 1] = Decode(parts[i]);
						}
						try {
							Execute(parts[0], arguments);
							output.WriteLine("OK");
						} catch (Exception e) {
							output.WriteLine("ERROR\\t" + Encode(e.GetType().Name + ": " + e.Message));
						}
					}

					foreach (DeviceSession session in sessions.Values) {
						session.Close();
					}
					sessions.Clear();
				}

				private static void Execute(string command, string[] arguments) {
					switch (command) {
						case "DEVICES":
							ListDevices();
							break;
						case "OPEN": {
							DeviceSession session = new DeviceSession(arguments[0]);
							string handle = (nextHandle++).ToString(CultureInfo.InvariantCulture);
							sessions[handle] = session;
							output.WriteLine("HANDLE\\t" + handle);
							break;
						}
						case "CLOSE": {
							DeviceSession session;
							if (sessions.TryGetValue(arguments[0], out session)) {
								sessions.Remove(arguments[0]);
								session.Close();
							}
							break;
						}
						case "LIST":
							GetSession(arguments[0]).List(NormalizePath(arguments[1]));
							break;
						case "STAT":
							GetSession(arguments[0]).Stat(NormalizePath(arguments[1]));
							break;
						case "DOWNLOAD":
							GetSession(arguments[0]).Download(NormalizePath(arguments[1]), arguments[2]);
							break;
						case "UPLOAD":
							GetSession(arguments[0]).Upload(arguments[1], NormalizePath(arguments[2]));
							break;
						case "DELETE":
							GetSession(arguments[0]).Delete(NormalizePath(arguments[1]), arguments[2] == "1");
							break;
						case "MKDIR":
							GetSession(arguments[0]).CreateDirectory(NormalizePath(arguments[1]));
							break;
						default:
							throw new ArgumentException("Unknown command: " + command);
					}
				}

				private static DeviceSession GetSession(string handle) {
					DeviceSession session;
					if (!sessions.TryGetValue(handle, out session)) {
						throw new InvalidOperationException("Device is not open (handle " + handle + ")");
					}
					return session;
				}

				private static void ListDevices() {
					IPortableDeviceManager manager = (IPortableDeviceManager) Wpd.CreateComObject(Wpd.CLSID_PortableDeviceManager);
					try {
						// The device manager caches its list, refresh it to see newly connected devices
						manager.RefreshDeviceList();
						uint count = 0;
						manager.GetDevices(IntPtr.Zero, ref count);
						if (count == 0) {
							return;
						}
						IntPtr pnpDeviceIds = Marshal.AllocHGlobal(IntPtr.Size * (int) count);
						try {
							manager.GetDevices(pnpDeviceIds, ref count);
							for (int i = 0; i < count; i++) {
								IntPtr pnpDeviceIdPointer = Marshal.ReadIntPtr(pnpDeviceIds, i * IntPtr.Size);
								string pnpDeviceId = Marshal.PtrToStringUni(pnpDeviceIdPointer);
								Marshal.FreeCoTaskMem(pnpDeviceIdPointer);
								WriteDevice(manager, pnpDeviceId);
							}
						} finally {
							Marshal.FreeHGlobal(pnpDeviceIds);
						}
					} finally {
						Marshal.ReleaseComObject(manager);
					}
				}

				private static void WriteDevice(IPortableDeviceManager manager, string pnpDeviceId) {
					string name = GetFriendlyName(manager, pnpDeviceId);
					string manufacturer = null;
					string model = null;
					string serialNumber = null;
					string powerLevel = null;
					string protocol = null;
					string errorMessage = null;
					try {
						IPortableDevice device = Wpd.OpenDevice(pnpDeviceId);
						try {
							IPortableDeviceContent content;
							device.Content(out content);
							IPortableDeviceProperties properties;
							content.Properties(out properties);
							IPortableDeviceValues values;
							// null requests all properties of the device object
							properties.GetValues(Wpd.DeviceObjectId, null, out values);
							manufacturer = Wpd.GetString(values, Wpd.DeviceManufacturer);
							model = Wpd.GetString(values, Wpd.DeviceModel);
							serialNumber = Wpd.GetString(values, Wpd.DeviceSerialNumber);
							protocol = Wpd.GetString(values, Wpd.DeviceProtocol);
							uint? level = Wpd.GetUInt(values, Wpd.DevicePowerLevel);
							if (level.HasValue) {
								powerLevel = level.Value.ToString(CultureInfo.InvariantCulture);
							}
							if (string.IsNullOrEmpty(name)) {
								name = Wpd.GetString(values, Wpd.DeviceFriendlyName);
							}
						} finally {
							device.Close();
						}
					} catch (Exception e) {
						errorMessage = e.GetType().Name + ": " + e.Message;
					}
					if (string.IsNullOrEmpty(name)) {
						name = model ?? pnpDeviceId;
					}
					output.WriteLine("DEVICE\\t" + Encode(name) + "\\t" + Encode(manufacturer) + "\\t" + Encode(model) + "\\t" + Encode(serialNumber)
						+ "\\t" + (powerLevel ?? "") + "\\t" + Encode(protocol) + "\\t" + Encode(pnpDeviceId) + "\\t" + Encode(errorMessage));
				}

				private static string GetFriendlyName(IPortableDeviceManager manager, string pnpDeviceId) {
					try {
						// The first call determines the required buffer length in characters
						uint length = 0;
						manager.GetDeviceFriendlyName(pnpDeviceId, IntPtr.Zero, ref length);
						if (length == 0) {
							return null;
						}
						IntPtr buffer = Marshal.AllocHGlobal((int) length * 2);
						try {
							manager.GetDeviceFriendlyName(pnpDeviceId, buffer, ref length);
							return Marshal.PtrToStringUni(buffer);
						} finally {
							Marshal.FreeHGlobal(buffer);
						}
					} catch (COMException) {
						return null;
					}
				}

				public static void WriteItem(MtpItem item, string path) {
					output.WriteLine("ITEM\\t" + Encode(path) + "\\t" + Encode(item.Name) + "\\t" + (item.IsDirectory ? "1" : "0") + "\\t"
						+ item.Size.ToString(CultureInfo.InvariantCulture) + "\\t"
						+ (item.Modified.HasValue ? item.Modified.Value.ToString(DateFormat, CultureInfo.InvariantCulture) : "") + "\\t"
						+ Encode(item.Id));
				}

				private static string NormalizePath(string path) {
					StringBuilder result = new StringBuilder();
					foreach (string segment in path.Replace('\\\\', '/').Split('/')) {
						if (segment.Length > 0) {
							result.Append('/').Append(segment);
						}
					}
					return result.Length == 0 ? "/" : result.ToString();
				}

				private static string Encode(string value) {
					return Convert.ToBase64String(Encoding.UTF8.GetBytes(value ?? ""));
				}

				private static string Decode(string value) {
					return Encoding.UTF8.GetString(Convert.FromBase64String(value));
				}
			}
		}
		""";

	private final Process process;
	private final BufferedReader reader;
	private final Writer writer;
	private boolean closed = false;

	/**
	 * Starts the helper process.
	 */
	public MtpManager() throws IOException {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
			throw new UnsupportedOperationException("MTP access via WPD is only available on Windows");
		}

		// The C# source is too large for the Windows command line limit (32767 characters),
		// so it is passed via a temporary file and only a short bootstrap script goes on the command line
		final Path sourceFile = Files.createTempFile("MtpManager", ".cs");
		try {
			Files.writeString(sourceFile, WPD_SERVER_SOURCE, StandardCharsets.UTF_8);

			final String script = String.join("\n",
				"$ErrorActionPreference = 'Stop'",
				"Add-Type -Path '" + sourceFile.toAbsolutePath().toString().replace("'", "''") + "'",
				"[Soderer.Wpd.MtpServer]::Run()");

			// -EncodedCommand expects Base64 of UTF-16LE and avoids all command line quoting issues
			final String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
			// COM objects of WPD expect a single threaded apartment
			final ProcessBuilder processBuilder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Sta", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedScript);
			processBuilder.redirectErrorStream(true);
			process = processBuilder.start();
			reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

			// Wait until the interop code is compiled and the command loop is running
			final List<String> startupOutput = new ArrayList<>();
			String line;
			while ((line = reader.readLine()) != null && !"READY".equals(line)) {
				startupOutput.add(line);
			}
			if (line == null) {
				process.destroy();
				throw new IOException("MTP helper process could not be started: " + String.join("\n", startupOutput));
			}
		} finally {
			// The source is compiled once READY was received (or the start failed), so it is no longer needed
			try {
				Files.deleteIfExists(sourceFile);
			} catch (@SuppressWarnings("unused") final IOException e) {
				// Leftover temp file is harmless
			}
		}
	}

	/**
	 * Determines all connected MTP devices including their serial numbers.
	 * Each call queries the devices anew, so it can be used to detect newly connected devices.
	 */
	public synchronized List<MtpDevice> getConnectedDevices() throws IOException {
		final List<MtpDevice> result = new ArrayList<>();
		for (final String[] parts : execute("DEVICES")) {
			if (parts.length == 9 && "DEVICE".equals(parts[0])) {
				final String powerLevel = emptyToNull(parts[5]);
				result.add(new MtpDevice(decodeToNull(parts[1]), decodeToNull(parts[2]), decodeToNull(parts[3]), decodeToNull(parts[4]),
					powerLevel != null ? Integer.valueOf(powerLevel) : null,
					decodeToNull(parts[6]), decodeToNull(parts[7]), decodeToNull(parts[8])));
			}
		}
		return result;
	}

	/**
	 * Returns the connected device with the given serial number, or null if it is not connected.
	 *
	 * @param serialNumber serial number as shown in Explorer's device properties
	 */
	public MtpDevice getConnectedDevice(final String serialNumber) throws IOException {
		for (final MtpDevice device : getConnectedDevices()) {
			if (serialNumber.equalsIgnoreCase(device.serialNumber())) {
				return device;
			}
		}
		return null;
	}

	/**
	 * Opens the file system of a connected device. Several devices can be open at the same time.
	 *
	 * @param device device as returned by {@link #getConnectedDevices()}
	 */
	public MtpFileSystem open(final MtpDevice device) throws IOException {
		if (device.pnpDeviceId() == null) {
			throw new IllegalArgumentException("Device has no PnP device id: " + device.name());
		}
		return open(device.pnpDeviceId());
	}

	/**
	 * Opens the file system of a connected device. Several devices can be open at the same time.
	 *
	 * @param pnpDeviceId WPD device interface path, see {@link MtpDevice#pnpDeviceId()}
	 */
	public synchronized MtpFileSystem open(final String pnpDeviceId) throws IOException {
		for (final String[] parts : execute("OPEN", pnpDeviceId)) {
			if (parts.length == 2 && "HANDLE".equals(parts[0])) {
				return new MtpFileSystem(this, parts[1]);
			}
		}
		throw new IOException("Device was opened without handle: " + pnpDeviceId);
	}

	/**
	 * Ends the helper process. All file systems opened by this manager are closed with it.
	 */
	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			if (process.isAlive()) {
				writer.write("EXIT\n");
				writer.flush();
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			}
		} catch (@SuppressWarnings("unused") final IOException e) {
			process.destroyForcibly();
		} catch (@SuppressWarnings("unused") final InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Checks whether the helper process is still usable.
	 */
	public synchronized boolean isOpen() {
		return !closed && process.isAlive();
	}

	/**
	 * Sends a command to the helper process and returns the tab separated result lines (without the final "OK").
	 * Text arguments are transferred Base64 encoded, so they may contain any characters.
	 */
	synchronized List<String[]> execute(final String command, final String... arguments) throws IOException {
		if (closed) {
			throw new IOException("MTP manager is already closed");
		}

		final StringBuilder commandLine = new StringBuilder(command);
		for (final String argument : arguments) {
			commandLine.append('\t').append(Base64.getEncoder().encodeToString(argument.getBytes(StandardCharsets.UTF_8)));
		}
		writer.write(commandLine.append('\n').toString());
		writer.flush();

		final List<String[]> result = new ArrayList<>();
		final List<String> unexpectedOutput = new ArrayList<>();
		String line;
		while ((line = reader.readLine()) != null) {
			final String[] parts = line.split("\t", -1);
			if ("OK".equals(line)) {
				return result;
			} else if ("ERROR".equals(parts[0]) && parts.length == 2) {
				throw new IOException(decode(parts[1]));
			} else if ("ITEM".equals(parts[0]) || "DEVICE".equals(parts[0]) || "HANDLE".equals(parts[0])) {
				result.add(parts);
			} else if (!line.isBlank()) {
				// E.g. PowerShell error messages
				unexpectedOutput.add(line);
			}
		}
		closed = true;
		throw new IOException("MTP helper process terminated unexpectedly: " + String.join("\n", unexpectedOutput));
	}

	/**
	 * Converts an ITEM result line into an MtpFile.
	 */
	static MtpFile toMtpFile(final String[] parts) throws IOException {
		if (parts.length != 7 || !"ITEM".equals(parts[0])) {
			throw new IOException("Invalid item data from MTP helper process");
		}
		return new MtpFile(decode(parts[1]), decode(parts[2]), "1".equals(parts[3]), Long.parseLong(parts[4]),
			parts[5].isEmpty() ? null : LocalDateTime.parse(parts[5]), decode(parts[6]));
	}

	private static String decode(final String value) {
		return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
	}

	private static String decodeToNull(final String value) {
		return emptyToNull(decode(value));
	}

	private static String emptyToNull(final String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
