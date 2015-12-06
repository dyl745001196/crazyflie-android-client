/**
 *    ||          ____  _ __
 * +------+      / __ )(_) /_______________ _____  ___
 * | 0xBC |     / __  / / __/ ___/ ___/ __ `/_  / / _ \
 * +------+    / /_/ / / /_/ /__/ /  / /_/ / / /_/  __/
 *  ||  ||    /_____/_/\__/\___/_/   \__,_/ /___/\___/
 *
 * Copyright (C) 2015 Bitcraze AB
 *
 * Crazyflie Nano Quadcopter Client
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */

package se.bitcraze.crazyfliecontrol.bootloader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import se.bitcraze.crazyflielib.bootloader.Bootloader;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

public class FirmwareDownloader {

    private static final String LOG_TAG = "FirmwareDownloader";
    private Context mContext;
    public final static String DOWNLOAD_DIRECTORY = "CrazyflieControl";
    public final static String RELEASES_JSON = "cf_releases.json";
    public final static String RELEASE_URL = "https://api.github.com/repos/bitcraze/crazyflie-firmware/releases";
    private List<Firmware> mFirmwares = new ArrayList<Firmware>();
    private long mDownloadReference;
    private DownloadManager mManager;

    private List<FirmwareDownloadListener> mDownloadListeners;

    public FirmwareDownloader(Context context) {
        this.mContext = context;
        this.mDownloadListeners = Collections.synchronizedList(new LinkedList<FirmwareDownloadListener>());
    }

    public void checkForFirmwareUpdate() {
        File sdcard = Environment.getExternalStorageDirectory();
        File releasesFile = new File(sdcard, DOWNLOAD_DIRECTORY + "/" + RELEASES_JSON);

        if (isNetworkAvailable()) {
            Log.d(LOG_TAG, "Network connection available.");
            if (!isFileAlreadyDownloaded(RELEASES_JSON) || isFileTooOld(releasesFile, 21600000)) {
                ((BootloaderActivity) mContext).appendConsole("Checking for updates...");
                new DownloadWebpageTask().execute(RELEASE_URL);
            } else {
                loadLocalFile(releasesFile);
            }
        } else {
            Log.d(LOG_TAG, "Network connection not available.");
            if (isFileAlreadyDownloaded(RELEASES_JSON)) {
                loadLocalFile(releasesFile);
            } else {
                ((BootloaderActivity) mContext).appendConsoleError("No local file found.\nNo network connection available.\nPlease check your connectivity.");
            }
        }
    }

    /**
     * Check network connectivity
     *
     * @return true if network is available, false otherwise
     */
    private boolean isNetworkAvailable() {
        ConnectivityManager connMgr = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnected();
    }

    private void loadLocalFile(File releasesFile) {
        Log.d(LOG_TAG, "Loading releases JSON from local file...");
        String input = new String(Bootloader.readFile(releasesFile));
        try {
            mFirmwares = parseJson(input);
        } catch (JSONException e) {
            ((BootloaderActivity) mContext).appendConsole("Error during parsing JSON content.");
            return;
        }
        ((BootloaderActivity) mContext).updateFirmwareSpinner(mFirmwares);
        ((BootloaderActivity) mContext).appendConsole("Found " + mFirmwares.size() + " firmware files.");
    }

    private boolean isFileTooOld (File file, long time) {
        if (file.exists() && file.length() > 0) {
            return System.currentTimeMillis() - file.lastModified() > time;
        }
        return false;
    }

    private class DownloadWebpageTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... urls) {
            String input = null;
            try {
                input = downloadUrl(urls[0]);
                Log.d(LOG_TAG, "Releases JSON downloaded.");
                mFirmwares = parseJson(input);
            } catch (IOException ioe) {
                Log.d(LOG_TAG, ioe.getMessage());
                return "Unable to retrieve web page. Check your connectivity.";
            } catch (JSONException je) {
                Log.d(LOG_TAG, je.getMessage());
                return "Error during parsing JSON content.";
            }

            // Write JSON to disk
            try {
                writeToReleaseJsonFile(input);
                Log.d(LOG_TAG, "Wrote JSON file.");
            } catch (IOException ioe) {
                Log.d(LOG_TAG, ioe.getMessage());
                return "Unable to save JSON file.";
            }
            return "Found " + mFirmwares.size() + " firmware files.";
        }

        @Override
        protected void onPostExecute(String result) {
            ((BootloaderActivity) mContext).updateFirmwareSpinner(mFirmwares);
            ((BootloaderActivity) mContext).appendConsole(result);
        }
    }

    private void writeToReleaseJsonFile(String input) throws IOException {
        File sdcard = Environment.getExternalStorageDirectory();
        File releasesFile = new File(sdcard, DOWNLOAD_DIRECTORY + "/" + RELEASES_JSON);
        if (!releasesFile.exists()) {
            releasesFile.getParentFile().mkdirs();
            releasesFile.createNewFile();
        }
        PrintWriter out = new PrintWriter(releasesFile);
        out.println(input);
        out.flush();
        out.close();
    }

    public void downloadFirmware(Firmware selectedFirmware) {
        if (selectedFirmware != null) {

            if (isFileAlreadyDownloaded(selectedFirmware.getTagName() + "/" + selectedFirmware.getAssetName())) {
                notifyDownloadFinished();
                return;
            }

            String browserDownloadUrl = selectedFirmware.getBrowserDownloadUrl();
            downloadFile(browserDownloadUrl, selectedFirmware.getAssetName(), selectedFirmware.getTagName());
        } else {
            Log.d(LOG_TAG, "Selected firmware does not have assets.");
            ((BootloaderActivity) mContext).appendConsole("Selected firmware does not have assets.");
            return;
        }
    }

    /**
     * Base path is CrazyflieControl directory
     *
     * @param path
     * @return
     */
    public boolean isFileAlreadyDownloaded(String path) {
        File sdcard = Environment.getExternalStorageDirectory();
        File firmwareFile = new File(sdcard, DOWNLOAD_DIRECTORY + "/" + path);
        return firmwareFile.exists() && firmwareFile.length() > 0;
    }

    public void downloadFile (String url, String fileName, String tagName) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setDescription("Some description");
        request.setTitle(fileName);
        // in order for this if to run, you must use the android 3.2 to compile your app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
        }
        request.setDestinationInExternalPublicDir(DOWNLOAD_DIRECTORY, tagName + "/" + fileName);

        // get download service and enqueue file
        mManager = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
        mDownloadReference = mManager.enqueue(request);
    }

    private String downloadUrl(String myUrl) throws IOException {
        BufferedReader reader = null;
        StringBuilder builder = new StringBuilder();
        HttpClient client = new DefaultHttpClient();
        HttpGet httpGet = new HttpGet(myUrl);
        try {
            HttpResponse response = client.execute(httpGet);
            StatusLine statusLine = response.getStatusLine();
            int statusCode = statusLine.getStatusCode();

            if (statusCode == 200) {
                HttpEntity entity = response.getEntity();
                InputStream content = entity.getContent();
                reader = new BufferedReader(new InputStreamReader(content, "UTF-8"));

                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            } else {
                Log.d(LOG_TAG, "The response is: " + response);
                return "The response is: " + response;
            }
        } finally {
            // Makes sure that the InputStream is closed after the app is finished using it.
            if (reader != null) {
                reader.close();
            }
        }
        return builder.toString();
    }

    public List<Firmware> parseJson(String input) throws JSONException {

        List<Firmware> firmwares = new ArrayList<Firmware>();

        JSONArray releasesArray = new JSONArray(input);
        for (int i = 0; i < releasesArray.length(); i++) {
            JSONObject releaseObject = releasesArray.getJSONObject(i);
            String tagName = releaseObject.getString("tag_name");
            String name = releaseObject.getString("name");
            String createdAt = releaseObject.getString("created_at");
            JSONArray assetsArray = releaseObject.getJSONArray("assets");
            if (assetsArray != null && assetsArray.length() > 0) {
                for (int n = 0; n < assetsArray.length(); n++) {
                    JSONObject assetsObject = assetsArray.getJSONObject(n);
                    String assetName = assetsObject.getString("name");
                    int size = assetsObject.getInt("size");
                    String downloadURL = assetsObject.getString("browser_download_url");
                    // hardcoded filter for DFU zip file (find a better way to handle this)
                    if (assetName.contains("_dfu")) {
                        continue;
                    }
                    Firmware firmware = new Firmware(tagName, name, createdAt);
                    firmware.setAsset(assetName, size, downloadURL);
                    firmwares.add(firmware);
                }
            } else {
                // Filter out firmwares without assets
                Log.d(LOG_TAG, "Firmware " + tagName + " was filtered out, because it has no assets.");
            }
        }
        return firmwares;
    }

    public BroadcastReceiver onComplete = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            //check if the broadcast message is for our Enqueued download
//            long referenceId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
//            if(mDownloadReference == referenceId){
//            }
//            long id = intent.getStringExtra(DownloadManager.COLUMN_LOCAL_FILENAME);

            String action = intent.getAction();
            if (action.equals(DownloadManager.ACTION_DOWNLOAD_COMPLETE) ){
                Bundle extras = intent.getExtras();
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(extras.getLong(DownloadManager.EXTRA_DOWNLOAD_ID));
                Cursor c = mManager.query(q);

                if (c.moveToFirst()) {
                    int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        String filePath = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME));
                        String filename = filePath.substring(filePath.lastIndexOf('/')+1, filePath.length());

                        Toast.makeText(mContext, "Download successful: " + filename, Toast.LENGTH_SHORT).show();

//                        if (filename.endsWith(".zip")) {
//                            unzip(new File(filePath));
//                        }
                        notifyDownloadFinished();
                    }
                }
                c.close();
            }
        }
      };


      /* Download listener */

      public void addDownloadListener(FirmwareDownloadListener dl) {
          this.mDownloadListeners.add(dl);
      }

      public void removeDownloadListener(FirmwareDownloadListener dl) {
          this.mDownloadListeners.remove(dl);
      }

      public void notifyDownloadFinished() {
          for (FirmwareDownloadListener downloadListener : mDownloadListeners) {
              downloadListener.downloadFinished();
          }
      }

      public interface FirmwareDownloadListener {

          public void downloadFinished();

      }

}
