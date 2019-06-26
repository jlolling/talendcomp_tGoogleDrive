/**
 * Copyright 2015 Jan Lolling jan.lolling@gmail.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.jlo.talendcomp.google.drive;

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

import org.apache.log4j.Logger;

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
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.GenericJson;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Clock;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Files.Get;
import com.google.api.services.drive.DriveRequest;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.drive.model.User;

public class DriveHelper {
	
	private Logger logger = null;
	private static final Map<String, DriveHelper> clientCache = new HashMap<String, DriveHelper>();
	private final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	private final JsonFactory JSON_FACTORY = new JacksonFactory();
	private File keyFile; // *.p12 key file is needed
	private String clientSecretFile = null;
	private String accountEmail;
	private String applicationName = null;
	private boolean useServiceAccount = true;
	private boolean useApplicationClientID = false;
	private String credentialDataStoreDir = null;
	private long timeMillisOffsetToPast = 10000;
	private Drive driveService;
	private int timeoutInSeconds = 120;
	private static Map<String, String> mimeTypeMap = null;
	public static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
	private String lastDownloadedFilePath = null;
	private long lastDownloadedFileSize = 0;
	private int maxRetriesInCaseOfErrors = 1;
	private int currentAttempt = 0;
	private long innerLoopWaitInterval = 100;
	private int errorCode = 0;
	private String errorMessage = null;
	private boolean useOnlyDriveFileScopeForClientId = false;
	
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
				(useOnlyDriveFileScopeForClientId ? Arrays.asList(DriveScopes.DRIVE_FILE) : Arrays.asList(DriveScopes.DRIVE, DriveScopes.DRIVE_FILE)))
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

	/**
	 * Initialize the Drive service client
	 * Depending of the settings a service account or a client-Id for native applications will be used
	 * @throws Exception
	 */
	public void initializeClient() throws Exception {
		// Authorization.
		final Credential credential;
		if (useServiceAccount) {
			credential = authorizeWithServiceAccount();
		} else if (useApplicationClientID) {
			credential = authorizeWithClientSecret();
		} else {
			throw new IllegalStateException("No authorisation method set!");
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
	
	/**
	 * Move a file to another folder on the Drive
	 * @param driveFilePathToMove source file path
	 * @param driveTargetFolderPath path to the target folder
	 * @param createIfNotExists if true missing target folder will be created
	 * @return meta data of the target file
	 * @throws Exception
	 */
	public com.google.api.services.drive.model.File moveToFolderByName(String driveFilePathToMove, String driveTargetFolderPath, boolean createIfNotExists) throws Exception {
		com.google.api.services.drive.model.File fileToMove = getByName(driveFilePathToMove);
		if (fileToMove == null) {
			throw new Exception("File " + driveFilePathToMove + " does not extsis in the Drive.");
		} else {
			com.google.api.services.drive.model.File targetFolder = getFolder(driveTargetFolderPath, createIfNotExists);
			if (targetFolder == null) {
				throw new Exception("Target folder " + driveTargetFolderPath + " does not exists or cannot be created.");
			} else {
				return moveToFolder(fileToMove, targetFolder.getId());
			}
		}
	}
	
	/**
	 * Move a file to another folder on the Drive
	 * @param fileId source file Id
	 * @param driveTargetFolderPath path to the target folder
	 * @param createIfNotExists if true missing target folder will be created
	 * @return meta data of the target file
	 * @throws Exception
	 */
	public com.google.api.services.drive.model.File moveToFolderById(String fileId, String driveTargetFolderPath, boolean createIfNotExists) throws Exception {
		com.google.api.services.drive.model.File fileToMove = getById(fileId);
		if (fileToMove == null) {
			throw new Exception("File with id=" + fileId + " does not extsis in the Drive.");
		} else {
			com.google.api.services.drive.model.File targetFolder = getFolder(driveTargetFolderPath, createIfNotExists);
			if (targetFolder == null) {
				throw new Exception("Target folder " + driveTargetFolderPath + " does not exists or cannot be created.");
			} else {
				return moveToFolder(fileToMove, targetFolder.getId());
			}
		}
	}

	private com.google.api.services.drive.model.File moveToFolder(com.google.api.services.drive.model.File fileToMove, String newParentId) throws Exception {
		checkPrerequisits();
		insertFileIntoFolder(newParentId, fileToMove);
		removeAllParentFolders(fileToMove, newParentId);
		Get request = driveService
				.files()
				.get(fileToMove.getId());
		return (com.google.api.services.drive.model.File) execute(request);
	}
	
	private void removeAllParentFolders(com.google.api.services.drive.model.File file, String exceptParentId) throws Exception {
		checkPrerequisits();
		if (file.getParents() != null) {
			for (String pr : file.getParents()) {
				if (pr.equals(exceptParentId) == false) {
					execute(driveService
						.parents()
						.delete(file.getId(), pr));
				}
			}
		}
	}
	
	private void insertFileIntoFolder(String folderId, com.google.api.services.drive.model.File file) throws Exception {
		checkPrerequisits();
		if (file.getParents() != null) {
			for (String pr : file.getParents()) {
				if (folderId.equals(pr)) {
					throw new Exception("File id=" + file.getId() + " has already a parent reference to the folder-Id=" + folderId);
				}
			}
		}
		ParentReference newParent = new ParentReference();
		newParent.setId(folderId);
		execute(driveService
			.parents()
			.insert(file.getId(), newParent));
	}
	
	/**
	 * returns the folder referenced by its virtual name, can create the folder structure if not exists
	 * @param path the path to find / to create
	 * @param createIfNotExists if true create the path if not exists
	 * @return the file object of the leaf folder
	 * @throws Exception
	 */
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
		if (pathList.isEmpty()) {
			pathList.add("/");
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
			if (currentFolderName.equalsIgnoreCase(folder.getName())) {
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
	
	public static String getFirstParentId(com.google.api.services.drive.model.File file) {
		List<String> parentRefs = file.getParents();
		if (parentRefs != null && parentRefs.size() > 0) {
			return parentRefs.get(0);
		} else {
			return null;
		}
	}
	
	private List<com.google.api.services.drive.model.File> getChildFolders(List<com.google.api.services.drive.model.File> allFolders, String parentId) {
		List<com.google.api.services.drive.model.File> children = new ArrayList<com.google.api.services.drive.model.File>();
		for (com.google.api.services.drive.model.File folder : allFolders) {
			if (folder.getParents() != null) {
				for (String pr : folder.getParents()) {
					if (parentId != null) {
						if (parentId.equals(pr)) {
							children.add(folder);
							break;
						}
					} else if (pr == null) {
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
		checkPrerequisits();
		com.google.api.services.drive.model.File folder = new com.google.api.services.drive.model.File();
		folder.setName(title);
		folder.setMimeType(FOLDER_MIME_TYPE);
		if (parentId != null) {
			folder.setParents(Arrays.asList(parentId));
		}
		return (com.google.api.services.drive.model.File) execute(driveService.files().create(folder));
	}
		
	/**
	 * Upload a file to the google drive
	 * @param localFilePath local file path
	 * @param title the alternate title, if null the file name will be used as title
	 * @param driveFolderPath target folder
	 * @param createDirIfNecessary if true create the target folder if not exists
	 * @param overwrite overwrite an existing file with the same title, otherweise if a file already exists an exception will be thrown
	 * @return the meta data of the new file
	 * @throws Exception
	 */
	public com.google.api.services.drive.model.File upload(String localFilePath, String title, String driveFolderPath, boolean createDirIfNecessary, boolean overwrite) throws Exception {
		checkPrerequisits();
		if (localFilePath == null || localFilePath.trim().isEmpty()) {
			throw new IllegalArgumentException("localFilePath cannot be null or empty");
		}
		File localFile = new File(localFilePath);
		if (localFile.canRead() == false) {
			throw new Exception("Local upload file: " + localFile.getAbsolutePath() + " cannot be read.");
		}
		if (driveFolderPath != null && driveFolderPath.trim().isEmpty() == false) {
			driveFolderPath = driveFolderPath.trim();
			int pos = driveFolderPath.lastIndexOf("/");
			if (pos == driveFolderPath.length() - 1) {
				driveFolderPath = driveFolderPath.substring(0, pos); // cut up last /
			}
		}
		if (title != null) {
			title = title.trim();
		}
		if (title == null || title.isEmpty()) {
			title = localFile.getName();
		}
		String filePath = null;
		if (driveFolderPath != null) {
			filePath = driveFolderPath + "/" + title;
		} else {
			filePath = title;
		}
		com.google.api.services.drive.model.File existingFile = getByName(filePath);
		if (overwrite == false && existingFile != null) {
			throw new Exception("File " + existingFile.getName() + " already exists in the Drive. File-Id=" + existingFile.getId());
		}
		if (existingFile == null) {
			info("Upload new file " + localFile.getAbsolutePath());
			String parentId = null;
			if (driveFolderPath != null && driveFolderPath.trim().isEmpty() == false) {
				com.google.api.services.drive.model.File parentFolder = getFolder(driveFolderPath, createDirIfNecessary);
				if (parentFolder != null) {
					parentId = parentFolder.getId();
				} else {
					throw new Exception("Parent folder " + driveFolderPath + " does not exists or cannot be created.");
				}
			}
			com.google.api.services.drive.model.File uploadFile = new com.google.api.services.drive.model.File();
		    uploadFile.setName(title);
			if (parentId != null) {
				uploadFile.setParents(Arrays.asList(parentId));
			}
		    String mimeType = getMimeType(localFilePath);
		    FileContent mediaContent = new FileContent(mimeType, localFile);
		    Drive.Files.Create insertRequest = driveService
		    		.files()
		    		.create(uploadFile, mediaContent);
		    MediaHttpUploader uploader = insertRequest.getMediaHttpUploader();
		    uploader.setDirectUploadEnabled(false);
		    uploader.setProgressListener(new MediaHttpUploaderProgressListener() {
				
				@Override
				public void progressChanged(MediaHttpUploader uploader) throws IOException {
					info("File status: " + uploader.getUploadState());
					info("Bytes uploaded:" + uploader.getNumBytesUploaded());
				}
				
			});
		    return (com.google.api.services.drive.model.File) execute(insertRequest);
		} else {
			info("Upload existing file " + localFile.getAbsolutePath());
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
					info("File status: " + uploader.getUploadState());
					info("Bytes uploaded:" + uploader.getNumBytesUploaded());
				}
				
			});
		    return (com.google.api.services.drive.model.File) execute(updateRequest);
		}
	}
	
	/**
	 * Download a file to the local file system
	 * @param driveFilePath path of the file in the Drive
	 * @param localFolder target folder in file system
	 * @param newFileName if not null this will be the new file name
	 * @param createDirs if true missing folders will be created
	 * @return the loaded file meta data
	 * @throws Exception
	 */
	public com.google.api.services.drive.model.File downloadByName(String driveFilePath, String localFolder, String newFileName, boolean createDirs) throws Exception {
		com.google.api.services.drive.model.File file = getByName(driveFilePath);
		if (file == null) {
			throw new Exception("File " + driveFilePath + " does not exists in drive.");
		} else {
			return downloadById(file.getId(), localFolder, newFileName, createDirs);
		}
	}

	/**
	 * Download a file to the local file system
	 * @param fileId ID if the file in the Drive
	 * @param localFolder target folder in file system
	 * @param newFileName if not null this will be the new file name
	 * @param createDirs if true missing folders will be created
	 * @return the loaded file meta data
	 * @throws Exception
	 */
	public com.google.api.services.drive.model.File downloadById(String fileId, String localFolder, String newFileName, boolean createDirs) throws Exception {
		if (fileId == null || fileId.trim().isEmpty()) {
			throw new IllegalArgumentException("fileId cannot be null or empty");
		}
		if (localFolder == null || localFolder.trim().isEmpty()) {
			throw new IllegalArgumentException("localFolder cannot be null or empty");
		} else if ((localFolder.endsWith("/") || localFolder.endsWith("\\")) == false) {
			localFolder = localFolder + "/";
		}
		com.google.api.services.drive.model.File file = (com.google.api.services.drive.model.File) execute(driveService
				.files()
				.get(fileId));
		String downLoadFilePath = null;
		if (newFileName != null && newFileName.trim().isEmpty() == false) {
			downLoadFilePath = localFolder + newFileName;
		} else {
			downLoadFilePath = localFolder + file.getName();
		}
		if (file.getWebContentLink() != null) {
			downloadByUrl(file.getWebContentLink(), downLoadFilePath, createDirs);
		}
		return file;
	}
	
	/**
	 * Delete a file on the Drive
	 * @param driveFilePath the path within the drive
	 * @param ignoreMissing if true missing file will not throw an exception
	 * @return the meta data of he deleted file
	 * @throws Exception
	 */
	public com.google.api.services.drive.model.File deleteByName(String driveFilePath, boolean ignoreMissing) throws Exception {
		com.google.api.services.drive.model.File file = getByName(driveFilePath);
		if (file == null) {
			if (ignoreMissing == false) {
				throw new Exception("File with file path=" + driveFilePath + " does not exists in the drive");
			} else {
				return null;
			}
		} else {
			return deleteById(file.getId(), ignoreMissing);
		}
	}	
	
	/**
	 * Delete a file on the Drive
	 * @param fileId ID if he file
	 * @param ignoreMissing if true a missing file will not cause an exception
	 * @return the meta data of the deleted file
	 * @throws Exception
	 */
	public com.google.api.services.drive.model.File deleteById(String fileId, boolean ignoreMissing) throws Exception {
		checkPrerequisits();
		if (fileId == null || fileId.trim().isEmpty()) {
			throw new IllegalArgumentException("fileId cannot be null or empty");
		}
		com.google.api.services.drive.model.File file = getById(fileId);
		if (file != null) {
			execute(driveService
					.files()
					.delete(file.getId()));
			return file;
		} else {
			if (ignoreMissing == false) {
				throw new Exception("File with id=" + fileId + " does not exists in the drive");
			} else {
				return null;
			}
		}
	}
	
	/**
	 * Get the file meta data
	 * @param driveFilePath the file path on the Drive
	 * @return file meta data
	 * @throws Exception
	 */
	public com.google.api.services.drive.model.File getByName(String driveFilePath) throws Exception {
		checkPrerequisits();
		if (driveFilePath == null || driveFilePath.trim().isEmpty()) {
			throw new IllegalArgumentException("filePath cannot be null or empty");
		} else {
			driveFilePath = driveFilePath.trim();
		}
		int pos = driveFilePath.lastIndexOf('/');
		String folderPath = null;
		String title = driveFilePath;
		String parentId = null;
		if (pos > 0) {
			folderPath = driveFilePath.substring(0, pos);
			title = driveFilePath.substring(pos + 1);
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
		List<com.google.api.services.drive.model.File> files = executeRequest(request, null);
		if (files.size() > 0) {
			return files.get(0);
		} else {
			return null;
		}
	}
	
	/**
	 * Get the file meta data
	 * @param fileId ID of the file
	 * @return meta data
	 * @throws Exception
	 */
	public com.google.api.services.drive.model.File getById(String fileId) throws Exception {
		checkPrerequisits();
		if (fileId == null || fileId.trim().isEmpty()) {
			throw new IllegalArgumentException("fileId cannot be null or empty");
		}
		try {
			com.google.api.services.drive.model.File file = (com.google.api.services.drive.model.File) execute(driveService
					.files()
					.get(fileId));
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
		checkPrerequisits();
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
					info("File status: " + downloader.getDownloadState());
					info("Bytes downloaded:" + downloader.getNumBytesDownloaded());
				}
		    	
		    });
		    downloader.download(new GenericUrl(fileDownloadUrl), fileOut);
			if (fileOut != null) {
				fileOut.flush();
				fileOut.close();
				fileOut = null;
			}
		    lastDownloadedFilePath = localFile.getAbsolutePath();
		    lastDownloadedFileSize = localFile.length();
		} catch (Exception e) {
			if (fileOut != null) {
				fileOut.flush();
				fileOut.close();
				fileOut = null;
			}
			Thread.sleep(200);
			if (localFile.canWrite()) {
				System.err.println("Download failed. Remove incomplete file: " + localFile.getAbsolutePath());
				localFile.delete();
			}
			throw e;
		}
	}
	
	private List<com.google.api.services.drive.model.File> listAllFolders() throws Exception {
		checkPrerequisits();
		com.google.api.services.drive.Drive.Files.List request = driveService
				.files()
				.list();
		request.setQ("mimeType='" + FOLDER_MIME_TYPE + "' and trashed=false");
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
			String parentFolder,
			String mimeType) throws Exception {
		checkPrerequisits();
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
		if (mimeType != null) {
			if (q.length() > 0) {
				q.append(" and ");
			}
			q.append("(mimeType = '");
			q.append(mimeType);
			q.append("'");
			if (includeFolders) {
				q.append(" or mimeType = '");
				q.append(FOLDER_MIME_TYPE);
				q.append("'");
			}
			q.append(")");
		}
		// exclude trashed files
		if (q.length() > 0) {
			q.append(" and ");
		}
		q.append("trashed=false");
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
				FileList files = (FileList) execute(request);
				if (pattern != null) { // apply the local filter
					Matcher matcher = null;
					for (com.google.api.services.drive.model.File file : files.getItems()) {
						matcher = pattern.matcher(file.getName());
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
	
	private void setPermissions(String role, String fileId, String emails, boolean sendEmailNotification) throws Exception {
		if (emails != null && emails.trim().isEmpty() == false) {
			if ("owner".equals(role) || "reader".equals(role) || "writer".equals(role)) {
				checkPrerequisits();
				StringTokenizer st = new StringTokenizer(emails, ",;");
				while (st.hasMoreTokens()) {
					String email = st.nextToken();
					if (email != null && email.trim().isEmpty() == false) {
						Permission p = new Permission();
						p.setType("user");
						p.setValue(email);
						p.setRole(role);
						execute(driveService
							.permissions()
							.create(fileId, p)
							.setSendNotificationEmails(sendEmailNotification));
					}
				}
			} else {
				throw new IllegalArgumentException("Unknown role: " + role);
			}
		}
	}

	public void setPermissionAsOwner(String fileId, String emails, boolean sendEmailNotification) throws Exception {
		setPermissions("owner", fileId, emails, sendEmailNotification);
	}

	public void setPermissionAsReader(String fileId, String emails, boolean sendEmailNotification) throws Exception {
		setPermissions("reader", fileId, emails, sendEmailNotification);
	}

	public void setPermissionAsWriter(String fileId, String emails, boolean sendEmailNotification) throws Exception {
		setPermissions("writer", fileId, emails, sendEmailNotification);
	}

	public void setUseServiceAccount(boolean useServiceAccount) {
		this.useServiceAccount = useServiceAccount;
	}

	public void setClientSecretFile(String clientSecretFile) {
		this.clientSecretFile = clientSecretFile;
	}
	
	private static void loadMimeTypes() throws Exception {
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
	
	/**
	 * get the mime-type of a file
	 * @param filePath the file path (with file name)
	 * @return the mime-type according to the latest Apache Foundation definition
	 * @throws Exception
	 */
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
	
	/**
	 * builds a separated String from the list entries
	 * @param list
	 * @param separator
	 * @return the chained strings
	 */
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
	
	/**
	 * build a chained list of the email addresses of the Users
	 * @param list users
	 * @param separator
	 * @return chained string
	 */
	public static String buildChainForUsers(List<User> list, String separator) {
		if (list == null || list.isEmpty()) {
			return null;
		}
		boolean firstLoop = true;
		StringBuilder sb = new StringBuilder();
		for (User user : list) {
			if (firstLoop) {
				firstLoop = false;
			} else {
				sb.append(separator);
			}
			sb.append(user.getEmailAddress());
		}
		return sb.toString();
	}

	/**
	 * build a chained list of the email addresses of the writers
	 * @param list users
	 * @param separator
	 * @return chained string
	 */
	public static String buildChainForPermissionWriters(List<Permission> list, String separator) {
		if (list == null || list.isEmpty()) {
			return null;
		}
		boolean firstLoop = true;
		StringBuilder sb = new StringBuilder();
		for (Permission p : list) {
			if ("writer".equalsIgnoreCase(p.getRole())) {
				if (firstLoop) {
					firstLoop = false;
				} else {
					sb.append(separator);
				}
				sb.append(p.getEmailAddress());
			}
		}
		return sb.toString();
	}

	/**
	 * build a chained list of the email addresses of the writers
	 * @param list users
	 * @param separator
	 * @return chained string
	 */
	public static String buildChainForPermissionReaders(List<Permission> list, String separator) {
		if (list == null || list.isEmpty()) {
			return null;
		}
		boolean firstLoop = true;
		StringBuilder sb = new StringBuilder();
		for (Permission p : list) {
			if ("reader".equalsIgnoreCase(p.getRole())) {
				if (firstLoop) {
					firstLoop = false;
				} else {
					sb.append(separator);
				}
				sb.append(p.getEmailAddress());
			}
		}
		return sb.toString();
	}

	/**
	 * checks if the file object is a folder
	 * @param file
	 * @return true if folder
	 */
	public static boolean isFolder(com.google.api.services.drive.model.File file) {
		return FOLDER_MIME_TYPE.equals(file.getMimeType());
	}

	public void setTimeMillisOffsetToPast(long timeMillisOffsetToPast) {
		this.timeMillisOffsetToPast = timeMillisOffsetToPast;
	}

	public void setTimeoutInSeconds(int timeoutInSeconds) {
		this.timeoutInSeconds = timeoutInSeconds;
	}

	/**
	 * the file path of the last download file
	 * @return file path
	 */
	public String getLastDownloadedFilePath() {
		return lastDownloadedFilePath;
	}

	/**
	 * the size of the last download file
	 * @return size in byte
	 */
	public long getLastDownloadedFileSize() {
		return lastDownloadedFileSize;
	}

	/**
	 * returns the Drive service client to re-usage for other DriveHelper instances
	 * @return Drive instance
	 */
	public Drive getDriveService() {
		return driveService;
	}

	/**
	 * set the Drive instance 
	 * @param driveService
	 */
	public void setDriveService(Drive driveService) {
		if (driveService == null) {
			throw new IllegalArgumentException("Drive Service cannot be null!");
		}
		this.driveService = driveService;
	}
	
	private void checkPrerequisits() throws Exception {
		if (driveService == null) {
			throw new Exception("Drive service client not initialized or set!");
		}
	}

	public boolean isUseApplicationClientID() {
		return useApplicationClientID;
	}

	public void setUseApplicationClientID(boolean useApplicationClientID) {
		this.useApplicationClientID = useApplicationClientID;
	}

	public void info(String message) {
		if (logger != null) {
			logger.info(message);
		} else {
			System.out.println("INFO:" + message);
		}
	}
	
	public void debug(String message) {
		if (logger != null) {
			logger.debug(message);
		} else {
			System.out.println("DEBUG:" + message);
		}
	}

	public void warn(String message) {
		if (logger != null) {
			logger.warn(message);
		} else {
			System.err.println("WARN:" + message);
		}
	}
	
	public void error(String message) {
		error(message, null);
	}

	public void error(String message, Exception e) {
		if (logger != null) {
			if (e != null) {
				logger.error(message, e);
			} else {
				logger.error(message);
			}
		} else {
			System.err.println("ERROR:" + message);
		}
	}

	public void setLogger(Logger logger) {
		this.logger = logger;
	}

	/**
	 * sets the maximum attempts to execute a request in case of errors
	 * @param maxRetriesInCaseOfErrors
	 */
	public void setMaxRetriesInCaseOfErrors(Integer maxRetriesInCaseOfErrors) {
		if (maxRetriesInCaseOfErrors != null) {
			this.maxRetriesInCaseOfErrors = maxRetriesInCaseOfErrors;
		}
	}

	private com.google.api.client.json.GenericJson execute(DriveRequest<?> request) throws IOException {
		try {
			Thread.sleep(innerLoopWaitInterval);
		} catch (InterruptedException e) {}
		com.google.api.client.json.GenericJson response = null;
		int waitTime = 1000;
		for (currentAttempt = 0; currentAttempt < maxRetriesInCaseOfErrors; currentAttempt++) {
			errorCode = 0;
			try {
				response = (GenericJson) request.execute();
				break;
			} catch (IOException ge) {
				warn("Got error:" + ge.getMessage());
				if (ge instanceof HttpResponseException) {
					errorCode = ((HttpResponseException) ge).getStatusCode();
					errorMessage = ((HttpResponseException) ge).getMessage();
				}
				if (Util.canBeIgnored(ge) == false) {
					error("Stop processing because of the error does not allow a retry.");
					throw ge;
				}
				if (currentAttempt == (maxRetriesInCaseOfErrors - 1)) {
					error("All repetitions of the request failed:" + ge.getMessage(), ge);
					throw ge;
				} else {
					// wait
					try {
						info("Retry request in " + waitTime + "ms");
						Thread.sleep(waitTime);
					} catch (InterruptedException ie) {}
					int random = (int) Math.random() * 500;
					waitTime = (waitTime * 2) + random;
				}
			}
		}
		return response;
	}

	public int getLastHttpStatusCode() {
		return errorCode;
	}

	public String getLastHttpStatusMessage() {
		return errorMessage;
	}

	public boolean isUseOnlyDriveFileScopeForClientId() {
		return useOnlyDriveFileScopeForClientId;
	}

	public void setUseOnlyDriveFileScopeForClientId(boolean useOnlyDriveFileScopeForClientId) {
		this.useOnlyDriveFileScopeForClientId = useOnlyDriveFileScopeForClientId;
	}

}