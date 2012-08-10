package me.grantland.twitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

import oauth.signpost.OAuth;
import oauth.signpost.OAuthConsumer;
import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer;
import oauth.signpost.commonshttp.HttpRequestAdapter;
import oauth.signpost.commonshttp.HttpResponseAdapter;
import oauth.signpost.exception.OAuthException;
import oauth.signpost.http.HttpRequest;
import oauth.signpost.http.HttpResponse;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;

/**
 * @author Grantland Chew
 */
public class Twitter {
    public static final String TAG = "me.grantland.twitter";
    public static final boolean DEBUG = false;

    public static final String REQUEST_TOKEN = "https://twitter.com/oauth/request_token";
    public static final String ACCESS_TOKEN = "https://twitter.com/oauth/access_token";
    public static final String AUTHORIZE = "https://twitter.com/oauth/authorize";
    public static final String DATA_API_URL = "http://api.twitter.com/";
    public static final String DENIED = "denied";
    public static final String CALLBACK_SCHEME = "gc";
    public static final String CALLBACK_URI = CALLBACK_SCHEME + "://twitt";

    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_CONSUMER = "consumer";
    public static final String EXTRA_AUTHORIZE_PARAMS = "params";
    public static final String EXTRA_ACCESS_KEY = "access_key";
    public static final String EXTRA_ACCESS_SECRET = "access_secret";

    // Used as default activityCode by authorize(). See authorize() below.
    public static final int DEFAULT_AUTH_ACTIVITY_CODE = 4242;

    private DefaultHttpClient mHttpClient = null;

    private OAuthConsumer mConsumer = null;

    private int mRequestCode;
    private DialogListener mListener = null;

    public Twitter(String consumerKey, String consumerSecret) {
        if (consumerKey == null || consumerSecret == null) {
            throw new IllegalArgumentException(
                    "You must specify your consumer key and secret when instantiating " +
                    "a Twitter object. See README for details.");
        }

        mHttpClient = new DefaultHttpClient();
        mHttpClient.getParams().setBooleanParameter("http.protocol.expect-continue", false);

        mConsumer = new CommonsHttpOAuthConsumer(consumerKey, consumerSecret);
    }

    /**
     * Short authorize method that uses default settings.
     *
     * Starts either an activity or dialog that a user will use to enter their credentials
     * to authorize your application with Twitter.
     *
     * @param activity
     *          The Activity to display the authorization window on.
     * @param listener
     *          The callback for Twitter authentication responses.
     * @return
     */
    public boolean authorize(Activity activity, final DialogListener listener) {
        return authorize(activity, false, null, DEFAULT_AUTH_ACTIVITY_CODE, listener);
    }

    /**
     * Short authorize method that uses the default activityCode.
     *
     * Starts either an activity or dialog that a user will use to enter their credentials
     * to authorize your application with Twitter.
     *
     * @param activity
     *          The Activity to display the authorization window on.
     * @param forceLogin
     *          Forces the user to enter their credentials to ensure the correct users account
     *          is authorized.
     * @param screenName
     *          Prefills the username input box of the OAuth login screen with the given value.
     * @param listener
     *          The callback for Twitter authentication responses.
     * @return
     */
    public boolean authorize(Activity activity, boolean forceLogin, String screenName, DialogListener listener) {
        return authorize(activity, forceLogin, screenName, DEFAULT_AUTH_ACTIVITY_CODE, listener);
    }

    /**
     * Full authorize method.
     *
     * Starts either an activity or dialog that a user will use to enter their credentials
     * to authorize your application with Twitter.
     *
     * @param activity
     *          The Activity to display the authorization window on.
     * @param forceLogin
     *          Forces the user to enter their credentials to ensure the correct users account
     *          is authorized.
     * @param screenName
     *          Prefills the username input box of the OAuth login screen with the given value.
     * @param activityCode
     *          The requestCode used in Activity#onActivityResult. Can be changed if the default
     *          conflicts with another Activity in your application.
     * @param listener
     *          The callback for Twitter authentication responses.
     * @return
     */
    public boolean authorize(Activity activity, boolean forceLogin, String screenName, int activityCode, DialogListener listener) {

        // Optional params
        String authorizeParams = "";
        if (forceLogin) {
            authorizeParams += "?force_login=" + forceLogin;
        }
        if (screenName != null) {
            authorizeParams += (authorizeParams.length() == 0 ? "&" : "?") + "screen_name=" + screenName;
        }
        if (DEBUG) Log.d(TAG, "authorize params: " + authorizeParams);

        // We could check if the activity exists in the manifest and fallback on
        // the dialog, but if a user wants to use the dialog they can.
        if (activityCode > 0) {
            startTwitterActivity(activity, authorizeParams, activityCode, listener);
        } else {
            startTwitterDialog(activity, authorizeParams, listener);
        }

        return true;
    }

    private boolean startTwitterActivity(Activity activity, String authorizeParams, int activityCode, DialogListener listener) {
        mRequestCode = activityCode;
        mListener = listener;

        Intent intent = new Intent(activity, TwitterActivity.class);
        intent.putExtra(EXTRA_CONSUMER, mConsumer);
        intent.putExtra(EXTRA_AUTHORIZE_PARAMS, authorizeParams);
        activity.startActivityForResult(intent, DEFAULT_AUTH_ACTIVITY_CODE);

        return true;
    }

    private boolean startTwitterDialog(Activity activity, String authorizeParams, final DialogListener listener) {
        TwitterDialog dialog = new TwitterDialog(activity, mConsumer, authorizeParams, new DialogListener() {
            public void onComplete(String token, String secret) {
                if (DEBUG) Log.d(TAG, "access_key: " + token);
                if (DEBUG) Log.d(TAG, "access_secret: " + secret);

                mConsumer.setTokenWithSecret(token, secret);

                listener.onComplete(token, token);
            }

            public void onError(TwitterError error) {
                listener.onError(error);
            }

            public void onCancel() {
                listener.onCancel();
            }
        });
        dialog.show();

        return true;
    }

    /**
     * Callback for Twitter authorize. Should be called in any Activity that calls
     * Twitter#authorize.
     *
     * @param requestCode
     *          The integer request code originally supplied to
     *          startActivityForResult(), allowing you to identify who this
     *          result came from.
     * @param resultCode
     *          The integer result code returned by the child activity
     *          through its setResult().
     * @param data
     *          An Intent, which can return result data to the caller
     *          (various data can be attached to Intent "extras").
     */
    public void authorizeCallback(int requestCode, int resultCode, Intent data) {
        if (mRequestCode != requestCode) {
            return;
        }

        String accessKey, accessSecret;

        if (Activity.RESULT_OK == resultCode) {
            String error = data.getStringExtra(EXTRA_ERROR);
            if (error != null) {
                mListener.onError(new TwitterError(error));
            } else {
                accessKey = data.getStringExtra(EXTRA_ACCESS_KEY);
                accessSecret = data.getStringExtra(EXTRA_ACCESS_SECRET);

                if (DEBUG) Log.d(TAG, "access_key: " + accessKey);
                if (DEBUG) Log.d(TAG, "access_secret: " + accessSecret);

                mConsumer.setTokenWithSecret(accessKey, accessSecret);

                if (mListener != null) {
                    mListener.onComplete(accessKey, accessSecret);
                    return;
                }
            }
        } else if (Activity.RESULT_CANCELED == resultCode) {
            if (mListener != null) {
                mListener.onCancel();
            }
        }
    }

    /**
     * Submit a request to the Twitter API. You should have valid authorization before calling
     * this method.
     *
     * @param method HTTP method, should be "GET" or "POST".
     * @param path Path to API endpoint. For example, "1/statuses/update".
     * @param parameters Any parameters to submit with the request, as key-value pairs.
     *                   For example, "status", "Hi!", "include_entities", "true".
     * @return The response string. This string is expected to be JSON-parsable.
     */
    public String request(String method, String path, String... parameters) {

        try {
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append(DATA_API_URL);
            urlBuilder.append(path);
            urlBuilder.append(".json");

            HttpRequest request = null;

            if (method == "GET") {
                String glue = "?";
                for (int i = 0; i < parameters.length - 1; i += 2) {
                    urlBuilder.append(glue);
                    urlBuilder.append(OAuth.percentEncode(parameters[i]).getBytes());
                    urlBuilder.append("=");
                    urlBuilder.append(OAuth.percentEncode(parameters[i + 1]).getBytes());
                    glue = "&";
                }

                HttpGet getRequest = new HttpGet(urlBuilder.toString());
                request = new HttpRequestAdapter(getRequest);
            } else if (method == "POST") {
                HttpPost postRequest = new HttpPost(urlBuilder.toString());

                Map<String, String> map = new HashMap<String, String>();
                for (int i = 0; i < parameters.length - 1; i += 2) {
                    map.put(parameters[i], parameters[i + 1]);
                }
                StringEntity body = new StringEntity(OAuth.formEncode(map.entrySet()));
                body.setContentType(new BasicHeader("Content-Type",
                                                    "application/x-www-form-urlencoded"));
                postRequest.setEntity(body);

                request = new HttpRequestAdapter(postRequest);
            } else {
                if (DEBUG) Log.d(TAG, "Invalid HTTP method: " + method);
                return "";
            }

            mConsumer.sign(request);

            HttpResponse response =
                new HttpResponseAdapter(mHttpClient.execute(((HttpUriRequest) request.unwrap())));

            if (DEBUG) Log.d(TAG, "Response code: " + response.getStatusCode());

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getContent()));

            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
                builder.append("\n");
            }
            return builder.toString();
        } catch (MalformedURLException exception) {
            if (DEBUG) Log.d(TAG, "Malformed URL exception: " + exception.toString());
            return "";
        } catch (IOException exception) {
            if (DEBUG) Log.d(TAG, "I/O exception: " + exception.toString());
            return "";
        } catch (OAuthException exception) {
            if (DEBUG) Log.d(TAG, "OAuth exception: " + exception.toString());
            return "";
        }
    }

    //==============================================================================================
    // Properties
    //==============================================================================================

    /**
     * @return boolean - whether this object has an non-expired session token.
     */
    public boolean isSessionValid() {
        return mConsumer != null && (getAccessToken() != null && getAccessTokenSecret() != null);
    }

    /**
     * @return String - the consumer_key.
     */
    public String getConsumerKey() {
        return mConsumer.getConsumerKey();
    }

    /**
     * @return String - the consumer_secret.
     */
    public String getConsumerSecret() {
        return mConsumer.getConsumerSecret();
    }

    /**
     * Sets the consumer_key and consumer_secret.
     * @param consumerKey
     * @param consumerSecret
     */
    public void setConsumerKeyAndSecret(String consumerKey, String consumerSecret) {
        mConsumer = new CommonsHttpOAuthConsumer(consumerKey, consumerSecret);
    }

    /**
     * @return String - the access_token.
     */
    public String getAccessToken() {
        return mConsumer.getToken();
    }

    /**
     * @return String - the access_token_secret.
     */
    public String getAccessTokenSecret() {
        return mConsumer.getTokenSecret();
    }

    /**
     * Sets the access_token and access_token_secret.
     * @param token
     * @param secret
     */
    public void setTokenWithSecret(String token, String secret) {
        mConsumer.setTokenWithSecret(token, secret);
    }


    /**
     * Callback interface for dialog requests.
     */
    public static interface DialogListener {

        /**
         * Called when a dialog completes.
         *
         * Executed by the thread that initiated the dialog.
         *
         * @param values
         *            Key-value string pairs extracted from the response.
         */
        public void onComplete(String accessKey, String accessSecret);

        /**
         * Called when a dialog has an error.
         *
         * Executed by the thread that initiated the dialog.
         */
        public void onError(TwitterError error);

        /**
         * Called when a dialog is canceled by the user.
         *
         * Executed by the thread that initiated the dialog.
         */
        public void onCancel();

    }
}
