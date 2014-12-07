package de.jlo.talendcomp.gdrive;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.media.MediaHttpDownloader;
import com.google.api.client.googleapis.media.MediaHttpDownloaderProgressListener;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Clock;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;

public class DriveHelper {
	
	private static final Map<String, DriveHelper> clientCache = new HashMap<String, DriveHelper>();
	private final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	private final JsonFactory JSON_FACTORY = new JacksonFactory();
	private File keyFile; // *.p12 key file is needed
	private String clientSecretFile = null;
	private String accountEmail;
	private String applicationName = null;
	private boolean useServiceAccount = true;
	private String credentialDataStoreDir = null;
	private long timeMillisOffsetToPast = 10000;
	private Drive driveService;
	private int timeoutInSeconds = 120;
	private boolean useDirectUpload = true;
	private boolean useDirectDownload = true;
	private static Map<String, String> mimeTypeMap = new HashMap<String, String>();
	public static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
	
	public static void putIntoCache(String key, DriveHelper client) {
		clientCache.put(key, client);
	}
	
	public static DriveHelper getFromCache(String key) {
		return clientCache.get(key);
	}

	public void setKeyFile(String file) {
		keyFile = new File(file);
	}

	public void setAccountEmail(String email) {
		accountEmail = email;
	}

	public String getApplicationName() {
		return applicationName;
	}

	public void setApplicationName(String applicationName) {
		this.applicationName = applicationName;
	}

	private Credential authorizeWithServiceAccount() throws Exception {
		if (keyFile == null) {
			throw new Exception("KeyFile not set!");
		}
		if (keyFile.canRead() == false) {
			throw new IOException("keyFile:" + keyFile.getAbsolutePath()
					+ " is not readable");
		}
		if (accountEmail == null || accountEmail.isEmpty()) {
			throw new Exception("account email cannot be null or empty");
		}
		// Authorization.
		return new GoogleCredential.Builder()
				.setTransport(HTTP_TRANSPORT)
				.setJsonFactory(JSON_FACTORY)
				.setServiceAccountId(accountEmail)
				.setServiceAccountScopes(Arrays.asList(DriveScopes.DRIVE, DriveScopes.DRIVE_FILE))
				.setServiceAccountPrivateKeyFromP12File(keyFile)
				.setClock(new Clock() {
					@Override
					public long currentTimeMillis() {
						// we must be sure, that we are always in the past from Googles point of view
						// otherwise we get an "invalid_grant" error
						return System.currentTimeMillis() - timeMillisOffsetToPast;
					}
				})
				.build();
	}

	/**
	 * Authorizes the installed application to access user's protected YouTube
	 * data.
	 * 
	 * @param scopes
	 *            list of scopes needed to access general and analytic YouTube
	 *            info.
	 */
	private Credential authorizeWithClientSecret() throws Exception {
		if (clientSecretFile == null) {
			throw new IllegalStateException("client secret file is not set");
		}
		File secretFile = new File(clientSecretFile);
		if (secretFile.exists() == false) {
			throw new Exception("Client secret file:" + secretFile.getAbsolutePath() + " does not exists or is not readable.");
		}
		Reader reader = new FileReader(secretFile);
		// Load client secrets.
		GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
		try {
			reader.close();
		} catch (Throwable e) {}
		// Checks that the defaults have been replaced (Default =
		// "Enter X here").
		if (clientSecrets.getDetails().getClientId().startsWith("Enter")
				|| clientSecrets
					.getDetails()
					.getClientSecret()
					.startsWith("Enter ")) {
			throw new Exception("The client secret file does not contains the credentials. At first you have to pass the web based authorization process!");
		}
		credentialDataStoreDir= secretFile.getParent() + "/" + clientSecrets.getDetails().getClientId() + "/";
		File credentialDataStoreDirFile = new File(credentialDataStoreDir);             
		if (credentialDataStoreDirFile.exists() == false && credentialDataStoreDirFile.mkdirs() == false) {
			throw new Exception("Credentedial data dir does not exists or cannot created:" + credentialDataStoreDir);
		}
		FileDataStoreFactory fdsf = new FileDataStoreFactory(credentialDataStoreDirFile);
		// Set up authorization code flow.
		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
				HTTP_TRANSPORT, 
				JSON_FACTORY, 
				clientSecrets, 
				Arrays.asList(DriveScopes.DRIVE, DriveScopes.DRIVE_FILE))
			.setDataStoreFactory(fdsf)
			.setClock(new Clock() {
				@Override
				public long currentTimeMillis() {
					// we must be sure, that we are always in the past from Googles point of view
					// otherwise we get an "invalid_grant" error
					return System.currentTimeMillis() - timeMillisOffsetToPast;
				}
			})
			.build();
		// Authorize.
		return new AuthorizationCodeInstalledApp(
				flow,
				new LocalServerReceiver()).authorize(accountEmail);
	}

	public void initializeClient() throws Exception {
		// Authorization.
		final Credential credential;
		if (useServiceAccount) {
			credential = authorizeWithServiceAccount();
		} else {
			credential = authorizeWithClientSecret();
		}
		driveService = new Drive.Builder(
				HTTP_TRANSPORT, 
				JSON_FACTORY, 	
				new HttpRequestInitializer() {
  					@Override
					public void initialize(final HttpRequest httpRequest) throws IOException {
						credential.initialize(httpRequest);
						httpRequest.setConnectTimeout(timeoutInSeconds * 1000);
						httpRequest.setReadTimeout(timeoutInSeconds * 1000);
					}
				})
	     		.setApplicationName(applicationName)
	     		.build();
	}
	
	public com.google.api.services.drive.model.File upload(String localFilePath, String title) throws Exception {
		File localFile = new File(localFilePath);
		if (localFile.canRead() == false) {
			throw new Exception("Local upload file: " + localFile.getAbsolutePath() + " cannot be read.");
		}
		com.google.api.services.drive.model.File fileMetadata = new com.google.api.services.drive.model.File();
		if (title != null && title.trim().isEmpty() == false) {
		    fileMetadata.setTitle(title);
		} else {
		    fileMetadata.setTitle(localFile.getName());
		}
	    String mimeType = getMimeType(localFilePath);
	    FileContent mediaContent = new FileContent(mimeType, localFile);
	    Drive.Files.Insert insert = driveService
	    		.files()
	    		.insert(fileMetadata, mediaContent);
	    MediaHttpUploader uploader = insert.getMediaHttpUploader();
	    uploader.setDirectUploadEnabled(useDirectUpload);
	    uploader.setProgressListener(new MediaHttpUploaderProgressListener() {
			
			@Override
			public void progressChanged(MediaHttpUploader uploader) throws IOException {
				System.out.println("File status: " + uploader.getUploadState());
				System.out.println("Bytes uploaded:" + uploader.getNumBytesUploaded());
			}
			
		});
	    return insert.execute();
	}

	public com.google.api.services.drive.model.File downloadById(String fileId, String localFilePath, boolean createDirs) throws Exception {
		com.google.api.services.drive.model.File file = driveService
				.files()
				.get(fileId)
				.execute();
		if (new File(localFilePath).isDirectory()) {
			localFilePath = localFilePath + "/" + file.getTitle();
		}
		if (file.getDownloadUrl() != null) {
			downloadByUrl(file.getDownloadUrl(), localFilePath, createDirs);
		}
		return file;
	}
	
	public com.google.api.services.drive.model.File delete(String fileId, boolean ignoreMissing) throws Exception {
		com.google.api.services.drive.model.File file = get(fileId);
		if (file != null) {
			driveService.files()
				.delete(file.getId())
				.execute();
			return file;
		} else {
			if (ignoreMissing == false) {
				throw new Exception("File with id=" + fileId + " does not exists in the drive");
			} else {
				return null;
			}
		}
	}
	
	public com.google.api.services.drive.model.File get(String fileId) throws Exception {
		try {
			com.google.api.services.drive.model.File file = driveService
					.files()
					.get(fileId)
					.execute();
			return file;
		} catch (com.google.api.client.googleapis.json.GoogleJsonResponseException ge) {
			if (ge.getStatusCode() == 404) {
				return null; // do not worry about a missing file
			} else {
				throw ge;
			}
		}
	}

	private void downloadByUrl(String fileDownloadUrl, String localFilePath, boolean createDirs) throws Exception {
		File localFile = new File(localFilePath);
		if (createDirs && localFile.getParentFile().exists() == false) {
			if (localFile.getParentFile().mkdirs() == false) {
				throw new Exception("Unable to create parent directory: " + localFile.getParent());
			}
		}
		OutputStream fileOut = new BufferedOutputStream(new FileOutputStream(localFile)); 
		try {
		    MediaHttpDownloader downloader = new MediaHttpDownloader(
		    		HTTP_TRANSPORT, 
		    		driveService.getRequestFactory().getInitializer());
		    downloader.setDirectDownloadEnabled(useDirectDownload);
		    downloader.setProgressListener(new MediaHttpDownloaderProgressListener() {

				@Override
				public void progressChanged(MediaHttpDownloader downloader)	throws IOException {
					System.out.println("File status: " + downloader.getDownloadState());
					System.out.println("Bytes downloaded:" + downloader.getNumBytesDownloaded());
				}
		    	
		    });
		    downloader.download(new GenericUrl(fileDownloadUrl), fileOut);
		} finally {
			if (fileOut != null) {
				fileOut.flush();
				fileOut.close();
			}
		}
	}
	
	/**
	 * Lists all files from the owner = accountEmail
	 * @return a list of File
	 * @throws Exception
	 */
	public List<com.google.api.services.drive.model.File> list(String regex, boolean caseSensitive) throws Exception {
		Pattern pattern = null;
		if (regex != null && regex.trim().isEmpty() == false) {
			pattern = Pattern.compile(regex, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
		}
		List<com.google.api.services.drive.model.File> resultList = new ArrayList<com.google.api.services.drive.model.File>();
		com.google.api.services.drive.Drive.Files.List request = driveService
				.files()
				.list();
		do {
			try {
				FileList files = request.execute();
				if (pattern != null) {
					Matcher matcher = null;
					for (com.google.api.services.drive.model.File file : files.getItems()) {
						matcher = pattern.matcher(file.getTitle());
						if (matcher.find()) {
							resultList.add(file);
						}
					}
				} else {
					resultList.addAll(files.getItems());
				}
				request.setPageToken(files.getNextPageToken());
			} catch (IOException e) {
				request.setPageToken(null);
				throw e;
			}
		} while (request.getPageToken() != null	&& request.getPageToken().length() > 0);
		return resultList;
	}

	public boolean isUseServiceAccount() {
		return useServiceAccount;
	}

	public void setUseServiceAccount(boolean useServiceAccount) {
		this.useServiceAccount = useServiceAccount;
	}

	public String getClientSecretFile() {
		return clientSecretFile;
	}

	public void setClientSecretFile(String clientSecretFile) {
		this.clientSecretFile = clientSecretFile;
	}
	
	public static void loadMimeTypes() throws Exception {
		InputStream in = DriveHelper.class.getResourceAsStream("mime.types");
		if (in == null) {
			throw new Exception("Resource mime.types could not be found");
		}
		BufferedReader br = new BufferedReader(new InputStreamReader(in));
		String line = null;
		mimeTypeMap = new HashMap<String, String>();
		while ((line = br.readLine()) != null) {
			if (line.startsWith("#") == false) {
				StringTokenizer st = new StringTokenizer(line, "\t");
				String mimeType = null;
				if (st.hasMoreElements()) {
					mimeType = st.nextToken().trim();
				}
				String extensions = null;
				if (st.hasMoreElements()) {
					extensions = st.nextToken().trim();
				}
				String[] extensionArray = extensions.split("\\s");
				for (String ext : extensionArray) {
					mimeTypeMap.put(ext, mimeType);
				}
			}
		}
	}
	
	public String getMimeType(String filePath) throws Exception {
		int pos = filePath.lastIndexOf('.');
		String fileExtension = null;
		if (pos > 0) {
			fileExtension = filePath.substring(pos);
		}
		if (fileExtension == null) {
			return "text/plain";
		}
		if (mimeTypeMap == null) {
			loadMimeTypes();
		}
		fileExtension = fileExtension.trim().toLowerCase();
		if (fileExtension.startsWith(".")) {
			fileExtension = fileExtension.substring(1);
		}
		return mimeTypeMap.get(fileExtension);
	}
	
	public static String buildChain(List<String> list, String separator) {
		if (list == null || list.isEmpty()) {
			return null;
		}
		boolean firstLoop = true;
		StringBuilder sb = new StringBuilder();
		for (String s : list) {
			if (firstLoop) {
				firstLoop = false;
			} else {
				sb.append(separator);
			}
			sb.append(s);
		}
		return sb.toString();
	}
	
	public static boolean isFolder(com.google.api.services.drive.model.File file) {
		return FOLDER_MIME_TYPE.equals(file.getMimeType());
	}

	public long getTimeMillisOffsetToPast() {
		return timeMillisOffsetToPast;
	}

	public void setTimeMillisOffsetToPast(long timeMillisOffsetToPast) {
		this.timeMillisOffsetToPast = timeMillisOffsetToPast;
	}

	public int getTimeoutInSeconds() {
		return timeoutInSeconds;
	}

	public void setTimeoutInSeconds(int timeoutInSeconds) {
		this.timeoutInSeconds = timeoutInSeconds;
	}

}