package org.fst2015pm.swbforms.extractors;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.UUID;
import java.util.logging.Logger;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.fst2015pm.swbforms.utils.FSTUtils;
import org.semanticwb.datamanager.DataList;
import org.semanticwb.datamanager.DataMgr;
import org.semanticwb.datamanager.DataObject;
import org.semanticwb.datamanager.SWBDataSource;
import org.semanticwb.datamanager.SWBScriptEngine;

import java.text.SimpleDateFormat;

/**
 * Base class for extractors. Implements methods from PMExtractor interface.
 * @author Hasdai Pacheco
 */
public class PMExtractorBase implements PMExtractor {
	static Logger log = Logger.getLogger(PMExtractorBase.class.getName());

	protected DataObject extractorDef;
	private SWBDataSource ds;
	public static enum STATUS {
		LOADED, STARTED, EXTRACTING, STOPPED, ABORTED, FAILLOAD
	}
	private STATUS status;
	private SimpleDateFormat sdf = new SimpleDateFormat("YYYY-MM-dd hh:mm:ss");

	/**
	 * Constructor. Creates a new instance of PMExtractorBase.
	 */
	public PMExtractorBase(DataObject def) {
		extractorDef = def;

		String dsName = def.getString("dataSource");
		SWBScriptEngine eng = DataMgr.initPlatform("/WEB-INF/dbdatasources.js", null);

		ds = eng.getDataSource(dsName);

		if (null == ds) { //try to load it from app datasources file
			status = STATUS.FAILLOAD;
		} else {
			log.info("LOADED extractor "+getName());
			status = STATUS.LOADED;
		}
	}

	@Override
	public String getName() {
		return null != extractorDef ? extractorDef.getString("name") : null;
	}

	/**
	 * Sets extractor definition.
	 * @param def Extractor definition.
	 */
	public void setExtractorDef(DataObject def) {
		extractorDef = def;
	}

	/**
	 * Gets extractor definition.
	 */
	public DataObject getExtractorDef() {
		return extractorDef;
	}

	@Override
	public String getStatus() {
		return status.toString();
	}

	@Override
	public void extract() throws IOException {
		if (status == STATUS.EXTRACTING) return; //Prevent data overwrite
		status = STATUS.EXTRACTING;

		// Get scriptObject configuration parameters
		String fileUrl = extractorDef.getString("fileLocation"); //Local path or URL of remote file
		boolean zipped = Boolean.valueOf(extractorDef.getString("zipped")); //Zipped flag
		boolean remote = false;

		if (null == fileUrl || fileUrl.isEmpty()) {
			status = STATUS.STARTED;
			return;
		}

		//Prepare file system
		String uuid = UUID.randomUUID().toString().replace("-", "");
		String destPath = org.apache.commons.io.FileUtils.getTempDirectoryPath();
		if (!destPath.endsWith("/")) destPath += "/";
		destPath += uuid;

		File destDir = new File(destPath);

		//Check if URL is provided
		URL url = null;
		try {
			url = new URL(fileUrl);
			remote = true;
		} catch (MalformedURLException muex) { }

		fileUrl = remote ? fileUrl : DataMgr.getApplicationPath() + fileUrl;

		//Get local or remote file, store in localPath
		if (remote) {
			log.info("PMExtractor :: Downloading resource "+ url +"...");
			destDir = new File(destPath,"tempFile"+(zipped ? "" : "." + getType().toLowerCase()));

			try {
				org.apache.commons.io.FileUtils.copyURLToFile(url, destDir, 5000, 5000);
			} catch (IOException ex) {
				ex.printStackTrace();
				status = STATUS.STARTED;
				return;
			}

			fileUrl = destPath + "/tempFile" + (zipped ? "" : "." + getType().toLowerCase());
		}

		if (zipped) {
			String zipPath = extractorDef.getString("zipPath");
			if (null == zipPath || zipPath.isEmpty()) { //No relative path provided
				org.apache.commons.io.FileUtils.deleteQuietly(new File(destPath));
				status = STATUS.STARTED;
				return;
			}
			log.info("PMExtractor :: Inflating file...");
			FSTUtils.ZIP.extractAll(fileUrl, destPath);
			//destPath += zipPath;
		}

		//Store data
		log.info("PMExtractor :: Storing data...");

		store(destPath);
		status = STATUS.STARTED;

		//Update execution date
		extractorDef.put("lastExecution", sdf.format(new Date()));

		//Update DBDatasource metadata
		SWBScriptEngine dbeng = DataMgr.initPlatform("/WEB-INF/dbdatasources.js", null);
		SWBDataSource dbds = dbeng.getDataSource("DBDataSource");
		if (null != dbds) {
			DataObject dsFetch = null;
			DataList dlist = null;

			try {
				DataObject wrapper = new DataObject();
				DataObject q = new DataObject();
				q.put("name", extractorDef.getString("dataSource"));

				wrapper.put("data", q);
				dsFetch = dbds.fetch(wrapper);

				if (null != dsFetch) {
					DataObject response = dsFetch.getDataObject("response");
					if (null != response) {
						dlist = response.getDataList("data");
					}
				}
				
				if (!dlist.isEmpty()) {
					DataObject dsource = dlist.getDataObject(0);
					dsource.put("updated", sdf.format(new Date()));
					dbds.updateObj(dsource);
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		
		ExtractorManager.datasource.updateObj(extractorDef);
	}

	public SWBDataSource getDataSource() {
		return ds;
	}

	@Override
	public boolean canStart() {
		return status != STATUS.FAILLOAD && (status == STATUS.STARTED || status == STATUS.STOPPED || status == STATUS.LOADED);
	}

	@Override
	public synchronized void start() {
		if (canStart()) {
			log.info("PMExtractor :: Started extractor " + getName());
			try {
				extract();
			} catch (IOException ioex) {
				status = STATUS.STARTED;
				ioex.printStackTrace();
			}
		}
	}

	@Override
	public synchronized void stop() {
		status = STATUS.STOPPED;
	}

	/**
	 * Sets extractor status flag.
	 * @param st Status flag.
	 */
	public synchronized void setStatus(STATUS st) {
		status = st;
	}

	/**
	 * Stores data in extracting phase
	 * @param filePath
	 * @throws IOException
	 */
	public void store(String filePath) throws IOException {
		throw new UnsupportedOperationException("Method not implemented");
	}

	public String getType() {
		throw new UnsupportedOperationException("Method not implemented");
	}

}
