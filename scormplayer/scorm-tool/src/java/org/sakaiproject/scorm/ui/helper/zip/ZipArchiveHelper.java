package org.sakaiproject.scorm.ui.helper.zip;

import org.apache.wicket.protocol.http.WebApplication;
import org.apache.wicket.spring.injection.annot.SpringComponentInjector;
import org.apache.wicket.util.file.Folder;
import org.sakaiproject.scorm.client.api.ScormClientFacade;
import org.sakaiproject.scorm.ui.helper.pages.UploadContentPackage;
import org.sakaiproject.scorm.ui.helper.zip.pages.UploadZipArchive;

public class ZipArchiveHelper extends WebApplication {

	private ScormClientFacade clientFacade;

	private Folder uploadFolder = null;

	protected void init()
	{
		addComponentInstantiationListener(new SpringComponentInjector(this));
		getResourceSettings().setThrowExceptionOnMissingResource(false);
		getDebugSettings().setAjaxDebugModeEnabled(false);

		mountBookmarkablePage("/upload", UploadContentPackage.class);
	}
	
	public Class getHomePage() {
		return UploadZipArchive.class;
	}
	
	public ScormClientFacade getClientFacade() {
		return clientFacade;
	}

	public void setClientFacade(ScormClientFacade clientFacade) {
		this.clientFacade = clientFacade;
	}

	public Folder getUploadFolder() {
		return uploadFolder;
	}
}
