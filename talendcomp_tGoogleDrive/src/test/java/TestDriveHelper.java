import java.io.IOException;

import com.google.api.services.drive.model.File;

import de.jlo.talendcomp.gdrive.DriveHelper;

public class TestDriveHelper {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		try {
//			testDownloadById();
			testUpload();
//			testDeleteById();
//			testGetById();
//			testCreateFolders();
//			testList();
//			testLoadMimeTypes();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private static DriveHelper client;
	
	private static DriveHelper getDriverHelper() throws Exception {
		if (client == null) {
			client = getHelperWithPersonalAccount();
		}
		return client;
	}

	private static DriveHelper getHelperWithServiceAccount() throws Exception {
		DriveHelper h = new DriveHelper();
		h.setApplicationName("GATalendComp");
		h.setAccountEmail("422451649636@developer.gserviceaccount.com");
		h.setKeyFile("/var/testdata/ga/config/af21f07c84b14af09c18837c5a385f8252cc9439-privatekey.p12");
		System.out.println("Initialise client...");
		h.initializeClient();
		return h;
	}
	
	private static DriveHelper getHelperWithPersonalAccount() throws Exception {
		DriveHelper h = new DriveHelper();
		h.setApplicationName("GATalendComp");
		h.setUseServiceAccount(false);
		h.setAccountEmail("jan.lolling@gmail.com");
		h.setClientSecretFile("/Volumes/Data/Talend/testdata/ga/config/client_secret_503880615382-ve9ac3176d2acre79tevkirt0v6pa91v.apps.googleusercontent.com.json");
		System.out.println("Initialise client...");
		h.initializeClient();
		return h;
	}

	public static void testList() throws Exception {
		DriveHelper h = getDriverHelper();
		System.out.println("List...");
		for (File f : h.list(null, false, null, null, null, null, null, null, false, "krieger-it")) {
			printOut(f);
		}
		System.out.println("Done.");
	}
	
	public static void testDownloadById() throws Exception {
		DriveHelper h = getDriverHelper();
		System.out.println("Get...");
		String fileId = "0B1aeMk_qSLEkcVhIRlRIN0tfRGM";
		File f = h.downloadById(fileId, "/Users/jan/Desktop/", true);
		printOut(f);
		System.out.println("Done.");
	}
	
	public static void testDeleteById() throws Exception {
		DriveHelper h = getDriverHelper();
		System.out.println("Delete...");
		h.delete("0B1aeMk_qSLEkY1NtV1BLUDJSS1k", false);
		System.out.println("Done.");
	}

	public static void testGetById() throws Exception {
		DriveHelper h = getDriverHelper();
		System.out.println("Get...");
		File f = h.get("0B1aeMk_qSLEkZGExREgxTTdYOG8");
		if (f != null) {
			printOut(f);
		}
		System.out.println("Done.");
	}

	public static void testUpload() throws Exception {
		DriveHelper h = getDriverHelper();
		System.out.println("Put...");
		File f = h.upload("/Volumes/Data/Talend/testdata/ga/drive/2008-02-14-REST--JUG-Berlin.pdf", null, null, false);
		printOut(f);
		System.out.println("Done.");
	}

	private static void printOut(File f) throws IOException {
		System.out.println("title=" + f.getTitle().replace('\n', ' '));
		System.out.println("id=" + f.getId());
		System.out.println("original file name=" + f.getOriginalFilename());
		System.out.println("file extension=" + f.getFileExtension());
//		System.out.println("self link=" + f.getSelfLink());
//		System.out.println("web content link=" + f.getWebContentLink());
//		System.out.println("web view link=" + f.getWebViewLink());
		System.out.println("created at=" + new java.util.Date(f.getCreatedDate().getValue()));
		System.out.println("file size=" + f.getFileSize());
//		System.out.println("downloadUrl=" + f.getDownloadUrl());
		System.out.println("mime.type=" + f.getMimeType());
		System.out.println("modified at=" + f.getModifiedDate());
		System.out.println("owner=" + f.getOwnerNames());
//		System.out.println(f.toPrettyString());
		System.out.println();
	}
	
	private static void downloadAll() throws Exception {
		DriveHelper h = getDriverHelper();
		System.out.println("Get...");
		for (File f : h.list(null, false, null, null, null, null, null, null, true, null)) {
			h.downloadById(f.getId(), "/Volumes/Data/Talend/testdata/ga/drive/mobile/" + f.getId() + ".txt", true);
		}
		System.out.println("Done.");
	}
	
	private static void testCreateFolders() throws Exception {
		DriveHelper h = getDriverHelper();
		System.out.println("Create folders...");
		String path = "/folder1/folder2/folder3";
		File dir = h.getFolder(path, true);
		printOut(dir);
	}
	
	private static void testLoadMimeTypes() throws Exception {
		DriveHelper.loadMimeTypes();
	}
	
}
