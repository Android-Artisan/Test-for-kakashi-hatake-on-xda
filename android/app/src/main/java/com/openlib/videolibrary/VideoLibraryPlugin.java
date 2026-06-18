package com.openlib.videolibrary;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Size;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native Android backend for the video library UI.
 *
 * Solid / well-tested paths: listVideos, listFolders, getThumbnail, playVideo,
 * requestPermission, and deleteVideos on Android 11+ (API 30+).
 *
 * Best-effort paths (documented in README): deleteVideos on API 24-29, and moveVideo
 * (relies on MediaStore.createWriteRequest, only available API 30+; no-ops with a
 * rejected promise on older versions).
 */
@CapacitorPlugin(
    name = "VideoLibrary",
    permissions = {
        @Permission(strings = { Manifest.permission.READ_MEDIA_VIDEO }, alias = "videos"),
        @Permission(strings = { Manifest.permission.READ_EXTERNAL_STORAGE }, alias = "videosLegacy")
    }
)
public class VideoLibraryPlugin extends Plugin {

    private static final int DELETE_REQUEST_CODE = 9001;
    private static final int WRITE_REQUEST_CODE = 9002;

    private String deleteCallbackId;
    private String moveCallbackId;
    private String pendingMoveId;
    private String pendingMoveTargetFolderName;

    private String currentAlias() {
        return Build.VERSION.SDK_INT >= 33 ? "videos" : "videosLegacy";
    }

    private boolean hasPermission() {
        return getPermissionState(currentAlias()) == PermissionState.GRANTED;
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (hasPermission()) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        requestPermissionForAlias(currentAlias(), call, "permissionCallback");
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", hasPermission());
        call.resolve(ret);
    }

    @PluginMethod
    public void listVideos(PluginCall call) {
        if (!hasPermission()) {
            call.reject("Permission not granted. Call requestPermission() first.");
            return;
        }
        Context ctx = getContext();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[] {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        };
        JSArray results = new JSArray();
        try (Cursor cursor = ctx.getContentResolver().query(
                collection, projection, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME));
                    long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION));
                    long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE));
                    long dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED));
                    String bucketId = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID));
                    String bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME));

                    Uri contentUri = ContentUris.withAppendedId(collection, id);

                    JSObject v = new JSObject();
                    v.put("id", String.valueOf(id));
                    v.put("title", name != null ? name : "Untitled");
                    v.put("durationMs", duration);
                    v.put("sizeBytes", size);
                    v.put("dateAdded", dateAdded * 1000L);
                    v.put("folderId", bucketId != null ? bucketId : "unknown");
                    v.put("folderName", bucketName != null ? bucketName : "Unknown");
                    v.put("uri", contentUri.toString());
                    results.put(v);
                }
            }
        } catch (Exception e) {
            call.reject("Failed to query videos: " + e.getMessage());
            return;
        }
        JSObject ret = new JSObject();
        ret.put("videos", results);
        call.resolve(ret);
    }

    @PluginMethod
    public void listFolders(PluginCall call) {
        if (!hasPermission()) {
            call.reject("Permission not granted. Call requestPermission() first.");
            return;
        }
        Context ctx = getContext();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[] {
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        };
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        try (Cursor cursor = ctx.getContentResolver().query(collection, projection, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String id = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME));
                    if (id == null) continue;
                    Integer existing = counts.get(id);
                    counts.put(id, existing == null ? 1 : existing + 1);
                    names.put(id, name != null ? name : "Unknown");
                }
            }
        } catch (Exception e) {
            call.reject("Failed to query folders: " + e.getMessage());
            return;
        }
        JSArray results = new JSArray();
        for (String id : counts.keySet()) {
            JSObject f = new JSObject();
            f.put("id", id);
            f.put("name", names.get(id));
            f.put("count", counts.get(id));
            results.put(f);
        }
        JSObject ret = new JSObject();
        ret.put("folders", results);
        call.resolve(ret);
    }

    @PluginMethod
    public void getThumbnail(PluginCall call) {
        String idStr = call.getString("id");
        if (idStr == null) {
            call.reject("id is required");
            return;
        }
        if (!hasPermission()) {
            call.reject("Permission not granted.");
            return;
        }
        try {
            long id = Long.parseLong(idStr);
            Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
            Bitmap bitmap;
            if (Build.VERSION.SDK_INT >= 29) {
                bitmap = getContext().getContentResolver().loadThumbnail(contentUri, new Size(320, 180), null);
            } else {
                bitmap = MediaStore.Video.Thumbnails.getThumbnail(
                        getContext().getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
            }
            if (bitmap == null) {
                call.reject("No thumbnail available");
                return;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            JSObject ret = new JSObject();
            ret.put("base64", "data:image/jpeg;base64," + base64);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to load thumbnail: " + e.getMessage());
        }
    }

    @PluginMethod
    public void playVideo(PluginCall call) {
        String uriStr = call.getString("uri");
        String title = call.getString("title", "Video");
        if (uriStr == null) {
            call.reject("uri is required");
            return;
        }
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("No activity available");
            return;
        }
        Intent intent = new Intent(getContext(), PlayerActivity.class);
        intent.putExtra("uri", uriStr);
        intent.putExtra("title", title);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void deleteVideos(PluginCall call) {
        JSArray idsArray = call.getArray("ids");
        if (idsArray == null) {
            call.reject("ids is required");
            return;
        }
        List<Uri> uris = new ArrayList<>();
        try {
            for (Object idObj : idsArray.toList()) {
                long id = Long.parseLong(String.valueOf(idObj));
                uris.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id));
            }
        } catch (Exception e) {
            call.reject("Invalid ids: " + e.getMessage());
            return;
        }

        if (uris.isEmpty()) {
            JSObject ret = new JSObject();
            ret.put("deleted", 0);
            call.resolve(ret);
            return;
        }

        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: ask the system for a single confirmation dialog covering
            // every selected item, regardless of which app created it.
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContext().getContentResolver(), uris);
                saveCall(call);
                deleteCallbackId = call.getCallbackId();
                Activity activity = getActivity();
                activity.startIntentSenderForResult(
                        pi.getIntentSender(), DELETE_REQUEST_CODE, null, 0, 0, 0);
            } catch (Exception e) {
                call.reject("Delete request failed: " + e.getMessage());
            }
        } else {
            // Pre-Android 11 best effort: works reliably for files this app created;
            // may silently skip files owned by other apps on API 29 without
            // surfacing the system's per-item confirmation flow.
            int deleted = 0;
            for (Uri u : uris) {
                try {
                    deleted += getContext().getContentResolver().delete(u, null, null);
                } catch (Exception ignored) {
                    // skipped: likely owned by another app and not user-confirmed
                }
            }
            JSObject ret = new JSObject();
            ret.put("deleted", deleted);
            call.resolve(ret);
        }
    }

    /**
     * Best-effort move: updates the RELATIVE_PATH column so the file shows up under a
     * different bucket/folder name. Requires Android 11+ (API 30+) because it relies on
     * MediaStore.createWriteRequest to get user consent for files the app doesn't own.
     */
    @PluginMethod
    public void moveVideo(PluginCall call) {
        String idStr = call.getString("id");
        String targetFolderName = call.getString("targetFolderName");
        if (idStr == null || targetFolderName == null) {
            call.reject("id and targetFolderName are required");
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            call.reject("Moving videos requires Android 11 or newer in this build.");
            return;
        }
        try {
            long id = Long.parseLong(idStr);
            Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
            List<Uri> uris = new ArrayList<>();
            uris.add(contentUri);

            PendingIntent pi = MediaStore.createWriteRequest(getContext().getContentResolver(), uris);
            pendingMoveId = idStr;
            pendingMoveTargetFolderName = targetFolderName;
            saveCall(call);
            moveCallbackId = call.getCallbackId();
            getActivity().startIntentSenderForResult(
                    pi.getIntentSender(), WRITE_REQUEST_CODE, null, 0, 0, 0);
        } catch (Exception e) {
            call.reject("Move request failed: " + e.getMessage());
        }
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);

        if (requestCode == DELETE_REQUEST_CODE) {
            if (deleteCallbackId == null) return;
            PluginCall savedCall = bridge.getSavedCall(deleteCallbackId);
            if (savedCall == null) return;
            JSObject ret = new JSObject();
            ret.put("confirmed", resultCode == Activity.RESULT_OK);
            savedCall.resolve(ret);
            bridge.releaseCall(savedCall);
            deleteCallbackId = null;
            return;
        }

        if (requestCode == WRITE_REQUEST_CODE) {
            if (moveCallbackId == null) return;
            PluginCall savedCall = bridge.getSavedCall(moveCallbackId);
            if (savedCall == null) return;
            if (resultCode != Activity.RESULT_OK) {
                savedCall.reject("User did not grant permission to modify this file.");
                bridge.releaseCall(savedCall);
                moveCallbackId = null;
                return;
            }
            try {
                long id = Long.parseLong(pendingMoveId);
                Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);

                android.content.ContentValues values = new android.content.ContentValues();
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/" + pendingMoveTargetFolderName + "/");
                int rows = getContext().getContentResolver().update(contentUri, values, null, null);

                JSObject ret = new JSObject();
                ret.put("moved", rows > 0);
                savedCall.resolve(ret);
            } catch (Exception e) {
                savedCall.reject("Move failed: " + e.getMessage());
            }
            bridge.releaseCall(savedCall);
            moveCallbackId = null;
        }
    }
}
