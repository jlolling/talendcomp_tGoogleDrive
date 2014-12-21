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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
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
import com.google.api.services.drive.model.ParentReference;

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
	private static Map<String, String> mimeTypeMap = null;
	public static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
	private String lastDownloadedFilePath = null;
	private long lastDownloadedFileSize = 0;
	
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
		// only if we do not already have a client
		if (driveService == null) {
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
	}
		
//	private void removeAllParentFolders(com.google.api.services.drive.model.File file) throws Exception {
//		if (file.getParents() != null) {
//			for (ParentReference pr : file.getParents()) {
//				driveService.parents().delete(file.getId(), pr.getId());
//			}
//		}
//	}
//	
//	private com.google.api.services.drive.model.ParentReference insertFileIntoFolder(String folderId, String fileId) throws Exception {
//		ParentReference newParent = new ParentReference();
//		newParent.setId(folderId);
//		return driveService.parents().insert(fileId, newParent).execute();
//	}
	
	public com.google.api.services.drive.model.File getFolder(String path, boolean createIfNotExists) throws Exception {
		if (path == null || path.trim().isEmpty()) {
			throw new IllegalArgumentException("path cannot be null or empty.");
		}
		List<com.google.api.services.drive.model.File> allFolders = listAllFolders();
		List<String> pathList = new ArrayList<String>();
		StringTokenizer st = new StringTokenizer(path, "/");
		while (st.hasMoreTokens()) {
			String f = st.nextToken();
			if (f.isEmpty() == false) {
				pathList.add(f);
			}
		}
		return getFolder(allFolders, null, pathList, 0, createIfNotExists);
	}
	
	private com.google.api.services.drive.model.File getFolder(
			List<com.google.api.services.drive.model.File> allFolders, 
			com.google.api.services.drive.model.File parent, 
			List<String> pathArray, 
			int level, 
			boolean createIfNotExists) throws Exception {
		com.google.api.services.drive.model.File child = null;
		String currentFolderName = pathArray.get(level);
		List<com.google.api.services.drive.model.File> childFolders = getChildFolders(allFolders, (parent != null ? parent.getId() : null));
		for (com.google.api.services.drive.model.File folder : childFolders) {
			if (currentFolderName.equalsIgnoreCase(folder.getTitle())) {
				child = folder;
				break;
			}
		}
		if (createIfNotExists && child == null) {
			// we have to create it
			child = createFolder((parent != null ? parent.getId() : null), currentFolderName);
			allFolders.add(child);
		}
		// check if we are at the end of the path array
		if (child != null) {
			if (level < pathArray.size() - 1) {
				// we have to continue to follow the path to the end
				child = getFolder(
						allFolders,
						child,
						pathArray,
						level + 1,
						createIfNotExists);
			}
		}
		return child;
	}
	
	private List<com.google.api.services.drive.model.File> getChildFolders(List<com.google.api.services.drive.model.File> allFolders, String parentId) {
		List<com.google.api.services.drive.model.File> children = new ArrayList<com.google.api.services.drive.model.File>();
		for (com.google.api.services.drive.model.File folder : allFolders) {
			if (folder.getParents() != null) {
				for (ParentReference pr : folder.getParents()) {
					if (parentId != null) {
						if (pr.getId().equals(parentId)) {
							children.add(folder);
							break;
						}
					} else if (pr.getIsRoot()) {
						children.add(folder);
						break;
					}
				}
			} else if (parentId == null) {
				children.add(folder);
			}
		}
		return children;
	}
	
	private com.google.api.services.drive.model.File createFolder(String parentId, String title) throws Exception {
		com.google.api.services.drive.model.File folder = new com.google.api.services.drive.model.File();
		folder.setTitle(title);
		folder.setMimeType(FOLDER_MIME_TYPE);
		if (parentId != null) {
			ParentReference pr = new ParentReference();
			pr.setId(parentId);
			List<ParentReference> parents = new ArrayList<ParentReference>();
			parents.add(pr);
			folder.setParents(parents);
		}
		return driveService.files().insert(folder).execute();
	}
		
	public com.google.api.services.drive.model.File upload(String localFilePath, String title, String parentPath, boolean createDirIfNecessary, boolean overwrite) throws Exception {
		if (localFilePath == null || localFilePath.trim().isEmpty()) {
			throw new IllegalArgumentException("localFilePath cannot be null or empty");
		}
		File localFile = new File(localFilePath);
		if (localFile.canRead() == false) {
			throw new Exception("Local upload file: " + localFile.getAbsolutePath() + " cannot be read.");
		}
		if (parentPath != null && parentPath.trim().isEmpty() == false) {
			parentPath = parentPath.trim();
			int pos = parentPath.lastIndexOf("/");
			if (pos == parentPath.length() - 1) {
				parentPath = parentPath.substring(0, pos); // cut up last /
			}
		}
		if (title != null) {
			title = title.trim();
		}
		if (title == null || title.isEmpty()) {
			title = localFile.getName();
		}
		String filePath = null;
		if (parentPath != null) {
			filePath = parentPath + "/" + title;
		} else {
			filePath = title;
		}
		com.google.api.services.drive.model.File existingFile = getByName(filePath);
		if (overwrite == false && existingFile != null) {
			throw new Exception("File " + existingFile.getTitle() + " already exists in the Drive. File-Id=" + existingFile.getId());
		}
		if (existingFile == null) {
			System.out.println("Upload new file " + localFile.getAbsolutePath());
			String parentId = null;
			if (parentPath != null && parentPath.trim().isEmpty() == false) {
				com.google.api.services.drive.model.File parentFolder = getFolder(parentPath, createDirIfNecessary);
				if (parentFolder != null) {
					parentId = parentFolder.getId();
				} else {
					throw new Exception("Parent folder " + parentPath + " does not exists or cannot be created.");
				}
			}
			com.google.api.services.drive.model.File uploadFile = new com.google.api.services.drive.model.File();
		    uploadFile.setTitle(title);
			if (parentId != null) {
				ParentReference pr = new ParentReference();
				pr.setId(parentId);
				List<ParentReference> parents = new ArrayList<ParentReference>();
				parents.add(pr);
				uploadFile.setParents(parents);
			}
		    String mimeType = getMimeType(localFilePath);
		    FileContent mediaContent = new FileContent(mimeType, localFile);
		    Drive.Files.Insert insertRequest = driveService
		    		.files()
		    		.insert(uploadFile, mediaContent);
		    MediaHttpUploader uploader = insertRequest.getMediaHttpUploader();
		    uploader.setDirectUploadEnabled(false);
		    uploader.setProgressListener(new MediaHttpUploaderProgressListener() {
				
				@Override
				public void progressChanged(MediaHttpUploader uploader) throws IOException {
					System.out.println("File status: " + uploader.getUploadState());
					System.out.println("Bytes uploaded:" + uploader.getNumBytesUploaded());
				}
				
			});
		    return insertRequest.execute();
		} else {
			System.out.println("Upload existing file " + localFile.getAbsolutePath());
		    String mimeType = getMimeType(localFilePath);
		    FileContent mediaContent = new FileContent(mimeType, localFile);
		    Drive.Files.Update updateRequest = driveService
		    		.files()
		    		.update(existingFile.getId(), existingFile, mediaContent);
		    MediaHttpUploader uploader = updateRequest.getMediaHttpUploader();
		    uploader.setDirectUploadEnabled(false);
		    uploader.setProgressListener(new MediaHttpUploaderProgressListener() {
				
				@Override
				public void progressChanged(MediaHttpUploader uploader) throws IOException {
					System.out.println("File status: " + uploader.getUploadState());
					System.out.println("Bytes uploaded:" + uploader.getNumBytesUploaded());
				}
				
			});
		    return updateRequest.execute();
		}
	}
	
	public com.google.api.services.drive.model.File downloadByName(String filePath, String localFolder, String newFileName, boolean createDirs) throws Exception {
		com.google.api.services.drive.model.File file = getByName(filePath);
		if (file == null) {
			throw new Exception("File " + filePath + " does not exists in drive.");
		} else {
			return downloadById(file.getId(), localFolder, newFileName, createDirs);
		}
	}

	public com.google.api.services.drive.model.File downloadById(String fileId, String localFolder, String newFileName, boolean createDirs) throws Exception {
		if (fileId == null || fileId.trim().isEmpty()) {
			throw new IllegalArgumentException("fileId cannot be null or empty");
		}
		if (localFolder == null || localFolder.trim().isEmpty()) {
			throw new IllegalArgumentException("localFolder cannot be null or empty");
		} else if ((localFolder.endsWith("/") || localFolder.endsWith("\\")) == false) {
			localFolder = localFolder + "/";
		}
		com.google.api.services.drive.model.File file = driveService
				.files()
				.get(fileId)
				.execute();
		String downLoadFilePath = null;
		if (newFileName != null && newFileName.trim().isEmpty() == false) {
			downLoadFilePath = localFolder + newFileName;
		} else {
			downLoadFilePath = localFolder + file.getTitle();
		}
		if (file.getDownloadUrl() != null) {
			downloadByUrl(file.getDownloadUrl(), downLoadFilePath, createDirs);
		}
		return file;
	}
	
	public com.google.api.services.drive.model.File deleteByName(String filePath, boolean ignoreMissing) throws Exception {
		int pos = filePath.lastIndexOf('/');
		String folderPath = null;
		String title = filePath;
		if (pos > 0) {
			folderPath = filePath.substring(0, pos);
			title = filePath.substring(pos);
		}
		List<com.google.api.services.drive.model.File> files = list(null, true, null, title, null, null, null, null, false, folderPath);
		if (files.size() > 0) {
			return files.get(0);
		} else {
			if (ignoreMissing == false) {
				throw new Exception("File with file path=" + filePath + " does not exists in the drive");
			} else {
				return null;
			}
		}
	}	
	
	public com.google.api.services.drive.model.File deleteById(String fileId, boolean ignoreMissing) throws Exception {
		if (fileId == null || fileId.trim().isEmpty()) {
			throw new IllegalArgumentException("fileId cannot be null or empty");
		}
		com.google.api.services.drive.model.File file = getById(fileId);
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
	
	public com.google.api.services.drive.model.File getByName(String filePath) throws Exception {
		if (filePath == null || filePath.trim().isEmpty()) {
			throw new IllegalArgumentException("filePath cannot be null or empty");
		} else {
			filePath = filePath.trim();
		}
		int pos = filePath.lastIndexOf('/');
		String folderPath = null;
		String title = filePath;
		String parentId = null;
		if (pos > 0) {
			folderPath = filePath.substring(0, pos);
			title = filePath.substring(pos + 1);
			com.google.api.services.drive.model.File parent = getFolder(folderPath, false);
			if (parent == null) {
				return null;
			} else {
				parentId = parent.getId();
			}
		}
		StringBuilder q = new StringBuilder();
		q.append("title='");
		q.append(title);
		q.append("' and trashed=false");
		if (parentId != null) {
			q.append(" and '");
			q.append(parentId);
			q.append("' in parents");
		}
		com.google.api.services.drive.Drive.Files.List request = driveService
				.files()
				.list();
		if (q.length() > 0) {
			request.setQ(q.toString().trim());
		}
		request.setCorpus("DEFAULT");
		List<com.google.api.services.drive.model.File> files = executeRequest(request, null);
		if (files.size() > 0) {
			return files.get(0);
		} else {
			return null;
		}
	}
	
	public com.google.api.services.drive.model.File getById(String fileId) throws Exception {
		if (fileId == null || fileId.trim().isEmpty()) {
			throw new IllegalArgumentException("fileId cannot be null or empty");
		}
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
		lastDownloadedFilePath = null;
		lastDownloadedFileSize = 0;
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
		    downloader.setDirectDownloadEnabled(false);
		    downloader.setProgressListener(new MediaHttpDownloaderProgressListener() {

				@Override
				public void progressChanged(MediaHttpDownloader downloader)	throws IOException {
					System.out.println("File status: " + downloader.getDownloadState());
					System.out.println("Bytes downloaded:" + downloader.getNumBytesDownloaded());
				}
		    	
		    });
		    downloader.download(new GenericUrl(fileDownloadUrl), fileOut);
		    lastDownloadedFilePath = localFile.getAbsolutePath();
		    lastDownloadedFileSize = localFile.length();
		} finally {
			if (fileOut != null) {
				fileOut.flush();
				fileOut.close();
			}
		}
	}
	
	private List<com.google.api.services.drive.model.File> listAllFolders() throws Exception {
		com.google.api.services.drive.Drive.Files.List request = driveService
				.files()
				.list();
		request.setQ("mimeType = '" + FOLDER_MIME_TYPE + "' and trashed = false");
		return executeRequest(request, null);
	}
	
	/**
	 * Lists all files from the owner = accountEmail
	 * @return a list of File
	 * @throws Exception
	 */
	public List<com.google.api.services.drive.model.File> list(
			String localFilterRegex, 
			boolean caseSensitive, 
			String qString, 
			String remoteFilter_titleStartsWith, 
			String remoteFilter_fullTextContains, 
			Date lastModifyedFrom, 
			Date lastModifyedUntil, 
			String owner, 
			boolean includeFolders,
			String parentFolder) throws Exception {
		// prepare the local filter
		Pattern pattern = null;
		if (localFilterRegex != null && localFilterRegex.trim().isEmpty() == false) {
			pattern = Pattern.compile(localFilterRegex, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
		}
		StringBuilder q = new StringBuilder();
		if (qString != null && qString.trim().isEmpty() == false) {
			q.append(qString);
		}
		if (owner != null && owner.trim().isEmpty() == false) {
			if (q.length() > 0) {
				q.append(" and ");
			}
			q.append("'");
			q.append(owner.trim());
			q.append("' in owners");
		}
		if (remoteFilter_titleStartsWith != null && remoteFilter_titleStartsWith.trim().isEmpty() == false) {
			if (q.length() > 0) {
				q.append(" and ");
			}
			q.append("title contains '");
			q.append(remoteFilter_titleStartsWith.trim());
			q.append("'");
		}
		if (remoteFilter_fullTextContains != null && remoteFilter_fullTextContains.trim().isEmpty() == false) {
			remoteFilter_fullTextContains = remoteFilter_fullTextContains.replace("\\", "\\\\");
			if (q.length() > 0) {
				q.append(" and ");
			}
			q.append("fullText contains '");
			q.append(remoteFilter_titleStartsWith);
			q.append("'");
		}
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		if (lastModifyedFrom != null) {
			if (q.length() > 0) {
				q.append(" and ");
			}
			q.append("modifiedDate >= '");
			q.append(sdf.format(lastModifyedFrom));
			q.append("'");
		}
		if (lastModifyedUntil != null) {
			if (q.length() > 0) {
				q.append(" and ");
			}
			q.append("modifiedDate < '");
			q.append(sdf.format(lastModifyedUntil));
			q.append("'");
		}
		if (includeFolders == false) {
			if (q.length() > 0) {
				q.append(" and ");
			}
			q.append("mimeType != '");
			q.append(FOLDER_MIME_TYPE);
			q.append("'");
		}
		// exclude trashed files
		if (q.length() > 0) {
			q.append(" and ");
		}
		q.append("trashed = false");
		if (parentFolder != null && parentFolder.trim().isEmpty() == false) {
			String parentId = null;
			com.google.api.services.drive.model.File parent = getFolder(parentFolder, false);
			if (parent != null) {
				parentId = parent.getId();
			} else {
				throw new Exception("Parent folder " + parentFolder + " does not exists.");
			}
			if (parentId != null) {
				if (q.length() > 0) {
					q.append(" and ");
				}
				q.append("'");
				q.append(parentId.trim());
				q.append("' in parents");
			}
		}
		com.google.api.services.drive.Drive.Files.List request = driveService
				.files()
				.list();
		if (q.length() > 0) {
			request.setQ(q.toString().trim());
		}
		request.setCorpus("DEFAULT");
		return executeRequest(request, pattern);
	}
	
	private List<com.google.api.services.drive.model.File> executeRequest(com.google.api.services.drive.Drive.Files.List request, Pattern pattern) throws Exception {
		List<com.google.api.services.drive.model.File> resultList = new ArrayList<com.google.api.services.drive.model.File>();
		do {
			try {
				FileList files = request.execute();
				if (pattern != null) { // apply the local filter
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
		InputStream in = DriveHelper.class.getResourceAsStream("/mime.types");
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

	public String getLastDownloadedFilePath() {
		return lastDownloadedFilePath;
	}

	public long getLastDownloadedFileSize() {
		return lastDownloadedFileSize;
	}

	public Drive getDriveService() {
		return driveService;
	}

	public void setDriveService(Drive driveService) {
		if (driveService == null) {
			throw new IllegalArgumentException("Drive Service cannot be null!");
		}
		this.driveService = driveService;
	}

}