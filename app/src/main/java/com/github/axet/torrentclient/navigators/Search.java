package com.github.axet.torrentclient.navigators;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.github.axet.androidlibrary.widgets.ThemeUtils;
import com.github.axet.torrentclient.R;
import com.github.axet.torrentclient.activities.MainActivity;
import com.github.axet.torrentclient.app.MainApplication;
import com.github.axet.torrentclient.app.SearchEngine;
import com.github.axet.torrentclient.dialogs.LoginDialogFragment;
import com.github.axet.torrentclient.dialogs.BrowserDialogFragment;
import com.github.axet.torrentclient.widgets.UnreadCountDrawable;

import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.HttpEntity;
import cz.msebera.android.httpclient.NameValuePair;
import cz.msebera.android.httpclient.client.CookieStore;
import cz.msebera.android.httpclient.client.config.RequestConfig;
import cz.msebera.android.httpclient.client.entity.UrlEncodedFormEntity;
import cz.msebera.android.httpclient.client.methods.AbstractExecutionAwareRequest;
import cz.msebera.android.httpclient.client.methods.CloseableHttpResponse;
import cz.msebera.android.httpclient.client.methods.HttpGet;
import cz.msebera.android.httpclient.client.methods.HttpPost;
import cz.msebera.android.httpclient.client.protocol.HttpClientContext;
import cz.msebera.android.httpclient.cookie.Cookie;
import cz.msebera.android.httpclient.entity.ContentType;
import cz.msebera.android.httpclient.impl.client.BasicCookieStore;
import cz.msebera.android.httpclient.impl.client.CloseableHttpClient;
import cz.msebera.android.httpclient.impl.client.HttpClientBuilder;
import cz.msebera.android.httpclient.impl.client.LaxRedirectStrategy;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie;
import cz.msebera.android.httpclient.message.BasicNameValuePair;
import cz.msebera.android.httpclient.util.EntityUtils;

public class Search extends BaseAdapter implements DialogInterface.OnDismissListener,
        UnreadCountDrawable.UnreadCount, MainActivity.NavigatorInterface {
    public static final String TAG = Search.class.getSimpleName();

    Context context;
    MainActivity main;
    ArrayList<SearchItem> list = new ArrayList<>();
    CloseableHttpClient httpclient;
    HttpClientContext httpClientContext = HttpClientContext.create();

    Thread thread;
    Looper threadLooper;
    AbstractExecutionAwareRequest request;

    WebView web;
    SearchEngine engine;
    Handler handler;

    String lastSearch; // last search request
    String lastLogin;// last login user name

    ArrayList<String> message = new ArrayList<>();

    // search header
    View header;
    ViewGroup message_panel;
    View message_close;
    ProgressBar header_progress; // progressbar / button
    View header_stop; // stop image
    View header_search; // search button
    TextView searchText;

    // footer data
    View footer;
    View footer_next; // load next button
    ProgressBar footer_progress; // progress bar / button
    View footer_stop; // stop image

    // load next data
    String next;
    ArrayList<String> nextLast = new ArrayList<>();

    public static class SearchItem {
        public String title;
        public String details;
        public String html;
        public String magnet;
        public String size;
        public String seed;
        public String leech;
        public String torrent;
        public Map<String, String> search;
    }

    public class Inject {
        @JavascriptInterface
        public void result(String html) {
            Log.d(TAG, "result()");
        }
    }

    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public Search(MainActivity m) {
        this.main = m;
        this.context = m;
        this.handler = new Handler();

        RequestConfig.Builder requestBuilder = RequestConfig.custom();
        requestBuilder = requestBuilder.setConnectTimeout(MainApplication.CONNECTION_TIMEOUT);
        requestBuilder = requestBuilder.setConnectionRequestTimeout(MainApplication.CONNECTION_TIMEOUT);

        this.httpclient = HttpClientBuilder.create()
                .setDefaultRequestConfig(requestBuilder.build())
                .setRedirectStrategy(new LaxRedirectStrategy())
                .build();
    }

    public void setEngine(SearchEngine engine) {
        this.engine = engine;
    }

    public SearchEngine getEngine() {
        return engine;
    }

    public void load(String state) {
        CookieStore cookieStore = httpClientContext.getCookieStore();
        if (cookieStore == null) {
            cookieStore = new BasicCookieStore();
            httpClientContext.setCookieStore(cookieStore);
        }
        cookieStore.clear();

        if (state.isEmpty())
            return;

        try {
            byte[] buf = Base64.decode(state, Base64.DEFAULT);
            ByteArrayInputStream bos = new ByteArrayInputStream(buf);
            ObjectInputStream oos = new ObjectInputStream(bos);
            int count = oos.readInt();
            for (int i = 0; i < count; i++) {
                Cookie c = (Cookie) oos.readObject();
                cookieStore.addCookie(c);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String save() {
        CookieStore cookieStore = httpClientContext.getCookieStore();
        // do not save cookies between restarts for non login
        if (cookieStore != null && engine.getMap("login") != null) {
            List<Cookie> cookies = cookieStore.getCookies();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos);
                oos.writeInt(cookies.size());
                for (Cookie c : cookies) {
                    oos.writeObject(c);
                }
                oos.flush();
                byte[] buf = bos.toByteArray();
                return Base64.encodeToString(buf, Base64.DEFAULT);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return "";
    }

    public void install(final ListView list) {
        list.setAdapter(null); // old phones crash to addHeader

        LayoutInflater inflater = LayoutInflater.from(context);

        header = inflater.inflate(R.layout.search_header, null, false);
        footer = inflater.inflate(R.layout.search_footer, null, false);

        footer_progress = (ProgressBar) footer.findViewById(R.id.search_footer_progress);
        footer_stop = footer.findViewById(R.id.search_footer_stop);
        footer_next = footer.findViewById(R.id.search_footer_next);
        footer_next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                footer_progress.setVisibility(View.VISIBLE);
                footer_stop.setVisibility(View.VISIBLE);
                footer_next.setVisibility(View.GONE);

                request(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Map<String, String> s = engine.getMap("search");

                            String url = next;
                            String html = get(url);

                            search(s, url, html, new Runnable() {
                                @Override
                                public void run() {
                                    // destory looper thread
                                    requestCancel();
                                }
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        // thread will be cleared by request()
                        updateLoadNext();
                    }
                });
            }
        });
        footer_progress.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestCancel();
            }
        });
        footer_next.setVisibility(View.GONE);
        if (thread == null) {
            footer_progress.setVisibility(View.GONE);
            footer_stop.setVisibility(View.GONE);
        } else {
            footer_progress.setVisibility(View.VISIBLE);
            footer_stop.setVisibility(View.VISIBLE);
        }

        message_panel = (ViewGroup) header.findViewById(R.id.search_header_message_panel);

        if (message.size() == 0) {
            message_panel.setVisibility(View.GONE);
        } else {
            message_panel.setVisibility(View.VISIBLE);
            message_panel.removeAllViews();
            for (int i = 0; i < message.size(); i++) {
                final String msg = message.get(i);

                final View v = inflater.inflate(R.layout.search_message, null);
                message_panel.addView(v);
                TextView text = (TextView) v.findViewById(R.id.search_header_message_text);
                text.setText(msg);

                message_close = v.findViewById(R.id.search_header_message_close);
                message_close.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View vv) {
                        message.remove(msg);
                        message_panel.removeView(v);
                        main.updateUnread();
                        notifyDataSetChanged();

                        if (message.size() == 0) {
                            message_panel.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }

        searchText = (TextView) header.findViewById(R.id.search_header_text);
        header_search = header.findViewById(R.id.search_header_search);
        header_progress = (ProgressBar) header.findViewById(R.id.search_header_progress);
        header_stop = header.findViewById(R.id.search_header_stop);

        searchText.setText(lastSearch);

        header_progress.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestCancel();
            }
        });

        updateHeaderButtons();

        header_search.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Search.this.list.clear();
                Search.this.nextLast.clear();
                footer_next.setVisibility(View.GONE);
                notifyDataSetChanged();

                request(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            search(searchText.getText().toString(), new Runnable() {
                                @Override
                                public void run() {
                                    // destory looper thread
                                    requestCancel();
                                }
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }, null);
            }
        });

        View login = header.findViewById(R.id.search_header_login);
        login.setVisibility(engine.getMap("login") == null ? View.GONE : View.VISIBLE);
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Map<String, String> login = Search.this.engine.getMap("login");

                String url = login.get("details");
                setCookies2WebView();

                String l = null;
                String p = null;

                if (login.get("post") != null) {
                    l = login.get("post_login");
                    p = login.get("post_password");
                }

                if (l == null && p == null) {
                    LoginDialogFragment d = LoginDialogFragment.create(url);
                    d.show(main.getSupportFragmentManager(), "");
                } else {
                    LoginDialogFragment d = LoginDialogFragment.create(url, lastLogin);
                    d.show(main.getSupportFragmentManager(), "");
                }
            }
        });

        list.addHeaderView(header);
        list.addFooterView(footer);

        list.setAdapter(this);

        handler.post(new Runnable() {
            @Override
            public void run() {
                // hide keyboard on search completed
                InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInputFromInputMethod(searchText.getWindowToken(), 0);
            }
        });
    }

    public void remove(ListView list) {
        lastSearch = searchText.getText().toString();
        list.removeHeaderView(header);
        list.removeFooterView(footer);
    }

    void updateHeaderButtons() {
        if (thread == null) {
            header_progress.setVisibility(View.GONE);
            header_stop.setVisibility(View.GONE);
            header_search.setVisibility(View.VISIBLE);
        } else {
            header_progress.setVisibility(View.VISIBLE);
            header_stop.setVisibility(View.VISIBLE);
            header_search.setVisibility(View.GONE);
        }
    }

    void updateLoadNext() {
        if (Search.this.next != null) {
            footer_next.setVisibility(View.VISIBLE);
        } else {
            footer_next.setVisibility(View.GONE);
        }
        footer_progress.setVisibility(View.GONE);
        footer_stop.setVisibility(View.GONE);
    }

    void requestCancel() {
        boolean i = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
            i = true;
        }
        if (threadLooper != null) {
            threadLooper.quit();
            threadLooper = null;
            i = true;
        }
        if (request != null) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    request.abort();
                    request = null;
                }
            });
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (i)
            Log.d(TAG, "interrupt");
    }

    void request(final Runnable run, final Runnable done) {
        requestCancel();

        header_progress.setVisibility(View.VISIBLE);
        header_stop.setVisibility(View.VISIBLE);
        header_search.setVisibility(View.GONE);

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Looper.prepare();
                    threadLooper = Looper.myLooper();
                    run.run();
                    Looper.loop();
                } catch (final RuntimeException e) {
                    if (thread != null) // ignore errors on abort()
                        error(e);
                } finally {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Log.d(TAG, "Destory Web");

                            if (web != null) {
                                web.destroy();
                                web = null;
                            }
                            // we are this thread, clear it
                            thread = null;
                            threadLooper = null;
                            request = null;

                            updateHeaderButtons();

                            if (done != null)
                                done.run();
                        }
                    });
                    Log.d(TAG, "Thread Exit");
                }
            }
        });
        thread.start();
    }

    public Context getContext() {
        return context;
    }

    public void update() {
        notifyDataSetChanged();
    }

    public void close() {
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (dialog instanceof LoginDialogFragment.Result) {
            final LoginDialogFragment.Result l = (LoginDialogFragment.Result) dialog;
            if (l.browser) {
                if (l.clear) {
                    CookieStore store = httpClientContext.getCookieStore();
                    if (store != null)
                        store.clear();
                }
                String url = engine.getMap("login").get("details");
                if (!setCookies2Apache(url)) {
                    // can return false only if cookes are empty
                    if (!l.clear) // did user clear? no error
                        error("Cookies are empty");
                }
            } else if (l.ok) {
                request(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            lastLogin = l.login;
                            login(l.login, l.pass, new Runnable() {
                                @Override
                                public void run() {
                                    requestCancel();
                                }
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }, null);
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return list.size();
    }

    @Override
    public SearchItem getItem(int i) {
        return list.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public View getView(final int position, View convertView, final ViewGroup parent) {
        LayoutInflater inflater = LayoutInflater.from(getContext());

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.search_item, parent, false);
        }

        final SearchItem item = getItem(position);

        TextView size = (TextView) convertView.findViewById(R.id.search_item_size);
        if (item.size == null || item.size.isEmpty()) {
            size.setVisibility(View.GONE);
        } else {
            size.setVisibility(View.VISIBLE);
            size.setText(item.size);
        }

        TextView seed = (TextView) convertView.findViewById(R.id.search_item_seed);
        if (item.seed == null || item.seed.isEmpty()) {
            seed.setVisibility(View.GONE);
        } else {
            seed.setVisibility(View.VISIBLE);
            seed.setText(context.getString(R.string.seed_tab) + " " + item.seed);
        }

        TextView leech = (TextView) convertView.findViewById(R.id.search_item_leech);
        if (item.leech == null || item.leech.isEmpty()) {
            leech.setVisibility(View.GONE);
        } else {
            leech.setVisibility(View.VISIBLE);
            leech.setText(context.getString(R.string.leech_tab) + " " + item.leech);
        }

        TextView text = (TextView) convertView.findViewById(R.id.search_item_name);
        text.setText(item.title);

        ImageView magnet = (ImageView) convertView.findViewById(R.id.search_item_magnet);
        magnet.setEnabled(false);
        magnet.setColorFilter(Color.GRAY);
        if (item.magnet != null) {
            magnet.setEnabled(true);
            magnet.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    main.addMagnet(item.magnet);
                }
            });
            magnet.setColorFilter(ThemeUtils.getThemeColor(getContext(), R.attr.colorAccent));
        }

        ImageView torrent = (ImageView) convertView.findViewById(R.id.search_item_torrent);
        torrent.setEnabled(false);
        torrent.setColorFilter(Color.GRAY);

        if (item.details != null) {
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String url = item.details;

                    setCookies2WebView();

                    final Map<String, String> s = engine.getMap("search");
                    String js = s.get("details_js");

                    BrowserDialogFragment d = BrowserDialogFragment.create(url, js);
                    d.show(main.getSupportFragmentManager(), "");
                }
            });
        }

        return convertView;
    }

    boolean setCookies2Apache(String url) {
        // longer url better, domain only can return null
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies == null || cookies.isEmpty()) {
            return false;
        }

        String[] cc = cookies.split(";");

        CookieStore apacheStore = httpClientContext.getCookieStore();
        if (apacheStore == null) {
            apacheStore = new BasicCookieStore();
            httpClientContext.setCookieStore(apacheStore);
        }

        // check if we have cookies with same name. if yes. then webview set cookie for a different URL.
        // then we have to remove old cookie from apache store (check value apache==webview) and
        // replace it with new cookie from webview. This is come from WebView restriction, when we do not know
        // exact cookie domain/path. and only can call getCookie(url)

        ArrayList<BasicClientCookie> webviewStore = new ArrayList<>();

        Uri uri = Uri.parse(url);

        for (String c : cc) {
            String[] vv = c.split("=");
            String n = null;
            if (vv.length > 0)
                n = vv[0].trim();
            String v = null;
            if (vv.length > 1)
                v = vv[1].trim();
            if (n == null)
                continue;
            BasicClientCookie cookie = new BasicClientCookie(n, v);
            // TODO it may cause troubles. Cookie maybe set for domain, .domain, www.domain or www.domain/path
            // and since we have to cut all www/path same name cookies with different paths will override.
            // need to check if returned cookie sting can contains DOMAIN/PATH values. Until then use domain only.
            cookie.setDomain(uri.getAuthority());
            webviewStore.add(cookie);
        }

        Set<String> dups = new TreeSet<>();

        for (int i = 0; i < webviewStore.size(); i++) {
            for (int k = 0; k < webviewStore.size(); k++) {
                if (k == i)
                    continue;
                String n1 = webviewStore.get(i).getName();
                if (n1.equals(webviewStore.get(k).getName()))
                    dups.add(n1);
            }
        }

        // find dups in Apache store. delete same cookie by values from WebView store
        List<Cookie> list = apacheStore.getCookies();
        for (String d : dups) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Cookie c = list.get(i);
                String n = c.getName();
                if (n.equals(d)) {
                    String v = c.getValue();
                    // remove WebView cookies, which name&&value == apache store
                    for (int k = webviewStore.size() - 1; k >= 0; k--) {
                        Cookie cw = webviewStore.get(k);
                        if (cw.getName().equals(n) && cw.getValue().equals(v)) {
                            webviewStore.remove(k);
                        }
                    }
                    // remove from apache store
                    BasicClientCookie rm = new BasicClientCookie(n, v);
                    rm.setPath(c.getPath());
                    rm.setDomain(c.getDomain());
                    rm.setExpiryDate(new Date(0));
                    apacheStore.addCookie(rm);
                }
            }
        }

        // add remaining cookies from WebView store to Apache store
        for (BasicClientCookie c : webviewStore) {
            apacheStore.addCookie(c);
        }

        if (dups.size() != 0) {
            if (Build.VERSION.SDK_INT >= 21)
                CookieManager.getInstance().removeAllCookies(null);
            else
                CookieManager.getInstance().removeAllCookie();
        }

        return true;
    }

    void setCookies2WebView() {
        CookieStore cookieStore = httpClientContext.getCookieStore();

        // share cookies back (Apache --> WebView)
        if (cookieStore != null) {
            CookieSyncManager.createInstance(getContext());
            CookieManager m = CookieManager.getInstance();
            List<Cookie> list = cookieStore.getCookies();
            for (int i = 0; i < list.size(); i++) {
                Cookie c = list.get(i);
                Uri.Builder b = new Uri.Builder();
                if (c.isSecure())
                    b.scheme("https");
                else
                    b.scheme("http");
                b.authority(c.getDomain());
                if (c.getPath() != null) {
                    b.appendPath(c.getPath());
                }
                String url = b.build().toString();
                m.setCookie(url, c.getName() + "=" + c.getValue());
            }
            CookieSyncManager.getInstance().sync();
        }
    }

    public void inject(String url, String html, String js, final Inject exec) {
        Log.d(TAG, "inject()");

        String result = ";\n\ntorrentclient.result(document.documentElement.outerHTML)";

        String script = null;
        if (js != null) {
            script = js + result;
        }

        final String inject = script;

        if (web != null) {
            web.destroy();
        }

        web = new WebView(context);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(final ConsoleMessage consoleMessage) {
                onConsoleMessage(consoleMessage.message(), consoleMessage.lineNumber(), consoleMessage.sourceId());
                return true;//super.onConsoleMessage(consoleMessage);
            }

            @Override
            public void onConsoleMessage(String msg, int lineNumber, String sourceID) {
                Log.d(TAG, msg);

                if (sourceID == null || sourceID.isEmpty())
                    error(msg);
            }

            @Override
            public boolean onJsAlert(WebView view, String url, final String message, JsResult result) {
                error(message);
                result.confirm();
                return true;//super.onJsAlert(view, url, message, result);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return super.shouldOverrideUrlLoading(view, url);
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                super.onLoadResource(view, url);
            }

            @TargetApi(Build.VERSION_CODES.M)
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                String str = error.getDescription().toString();
                Log.d(TAG, str);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                // on M will becalled above method
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    Log.d(TAG, description);
                }
            }

            @TargetApi(Build.VERSION_CODES.LOLLIPOP)
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                Log.d(TAG, "onPageCommitVisible");
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "onPageFinished");
                if (inject != null)
                    web.loadUrl("javascript:" + inject);
            }
        });
        web.addJavascriptInterface(exec, "torrentclient");
        // Uncaught SecurityError: Failed to read the 'cookie' property from 'Document': Cookies are disabled inside 'data:' URLs.
        // called when page loaded with loadData()
        web.loadDataWithBaseURL(url, html, "text/html", null, null);
    }

    public void login(String login, String pass, final Runnable done) throws IOException {
        final Map<String, String> s = engine.getMap("login");

        final String post = s.get("post");
        if (post != null) {
            String l = s.get("post_login");
            String p = s.get("post_password");
            String pp = s.get("post_params");
            HashMap<String, String> map = new HashMap<>();
            if (l != null)
                map.put(l, login);
            if (p != null)
                map.put(p, pass);
            String[] params = pp.split(";");
            for (String param : params) {
                String[] m = param.split("=");
                map.put(URLDecoder.decode(m[0].trim(), MainApplication.UTF8), URLDecoder.decode(m[1].trim(), MainApplication.UTF8));
            }
            final String html = post(post, map);

            final String js = s.get("js");
            if (js != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        inject(post, html, js, new Inject() {
                            @JavascriptInterface
                            public void result(String html) {
                                super.result(html);
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (done != null)
                                            done.run();
                                    }
                                });
                            }
                        });
                    }
                });
                return;
            }
        }

        if (done != null)
            done.run();
    }

    public void search(String search, Runnable done) throws IOException {
        Map<String, String> s = engine.getMap("search");

        String url = null;
        String html = null;

        String post = s.get("post");
        if (post != null) {
            String t = s.get("post_search");
            url = post;
            html = post(url, new String[][]{{t, search}});
        }

        String get = s.get("get");
        if (get != null) {
            String query = URLEncoder.encode(search, MainApplication.UTF8);
            url = String.format(get, query);
            html = get(url);
        }

        search(s, url, html, done);
    }

    public void search(final Map<String, String> s, final String url, final String html, final Runnable done) {
        this.nextLast.add(url);

        final String js = s.get("js");
        if (js != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    inject(url, html, js, new Inject() {
                        @JavascriptInterface
                        public void result(final String html) {
                            super.result(html);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        searchList(s, url, html);
                                    } catch (final RuntimeException e) {
                                        error(e);
                                    } finally {
                                        if (done != null)
                                            done.run();
                                    }
                                }
                            });
                        }
                    });
                }
            });
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    searchList(s, url, html);
                } catch (final RuntimeException e) {
                    error(e);
                } finally {
                    if (done != null)
                        done.run();
                }
            }
        });
    }

    void searchList(Map<String, String> s, String url, String html) {
        Document doc = Jsoup.parse(html);
        Elements list = doc.select(s.get("list"));
        for (int i = 0; i < list.size(); i++) {
            SearchItem item = new SearchItem();
            item.html = list.get(i).outerHtml();
            item.title = matcher(item.html, s.get("title"));
            item.magnet = matcher(item.html, s.get("magnet"));
            item.torrent = matcher(url, item.html, s.get("torrent"));
            item.size = matcher(item.html, s.get("size"));
            item.seed = matcher(item.html, s.get("seed"));
            item.leech = matcher(item.html, s.get("leech"));
            item.details = matcher(url, item.html, s.get("details"));

            // do not empty items
            if (isEmpty(item.title) && isEmpty(item.magnet) && isEmpty(item.torrent) && isEmpty(item.details))
                continue;

            this.list.add(item);
        }

        String next = matcher(url, html, s.get("next"));
        if (next != null) {
            for (String last : nextLast) {
                if (next.equals(last)) {
                    next = null;
                    break;
                }
            }
        }
        this.next = next;

        updateLoadNext();

        if (list.size() > 0) {
            // hide keyboard on search sucecful completed
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(searchText.getWindowToken(), 0);
        }

        notifyDataSetChanged();
    }

    String matcher(String url, String html, String q) {
        String m = matcher(html, q);

        if (m != null) {
            if (m.isEmpty()) {
                return null;
            } else {
                try {
                    URL u = new URL(url);
                    u = new URL(u, m);
                    m = u.toString();
                } catch (MalformedURLException e) {
                }
            }
        }

        return m;
    }

    String matcher(String html, String q) {
        if (q == null)
            return null;

        String all = "(.*)";
        String regex = "regex\\((.*)\\)";
        String child = "nth-child\\((.*)\\)";
        String last = "last";

        Boolean l = false;
        Integer e = null;
        String r = null;
        Pattern p = Pattern.compile(all + ":" + last + ":" + regex, Pattern.DOTALL);
        Matcher m = p.matcher(q);
        if (m.matches()) { // first we look for q:last:regex
            q = m.group(1);
            l = true;
            r = m.group(2);
        } else { // then we look for q:nth-child:regex
            p = Pattern.compile(all + ":" + child + ":" + regex, Pattern.DOTALL);
            m = p.matcher(q);
            if (m.matches()) {
                q = m.group(1);
                e = Integer.parseInt(m.group(2));
                r = m.group(3);
            } else { // then we look for q:regex
                p = Pattern.compile(all + ":" + regex, Pattern.DOTALL);
                m = p.matcher(q);
                if (m.matches()) {
                    q = m.group(1);
                    r = m.group(2);
                } else { // then for regex only
                    p = Pattern.compile(regex, Pattern.DOTALL);
                    m = p.matcher(q);
                    if (m.matches()) {
                        q = null;
                        r = m.group(1);
                    }
                }
            }
        }

        String a = "";

        if (q == null || q.isEmpty()) {
            a = html;
        } else {
            Document doc1 = Jsoup.parse(html, "", Parser.xmlParser());
            Elements list1 = doc1.select(q);
            if (list1.size() > 0) {
                if (l)
                    a = list1.get(list1.size() - 1).outerHtml();
                else if (e != null) {
                    int i = e - 1;
                    if (i < list1.size()) { // ignore offset
                        a = list1.get(i).outerHtml();
                    }
                } else
                    a = list1.get(0).outerHtml();
            }
        }

        if (r != null) {
            Pattern p1 = Pattern.compile(r, Pattern.DOTALL);
            Matcher m1 = p1.matcher(a);
            if (m1.matches()) {
                a = m1.group(1);
            } else {
                a = ""; // tell we did not find any regex match
            }
        }

        return Html.fromHtml(a).toString();
    }

    String get(String url) throws IOException {
        HttpGet httpGet = new HttpGet(url);
        request = httpGet;
        CloseableHttpResponse response = httpclient.execute(httpGet, httpClientContext);
        Log.d(TAG, response.getStatusLine().toString());
        HttpEntity entity = response.getEntity();
        ContentType contentType = ContentType.getOrDefault(entity);
        String html = IOUtils.toString(entity.getContent(), contentType.getCharset());
        EntityUtils.consume(entity);
        response.close();
        request = null;
        return html;
    }

    String post(String url, String[][] map) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < map.length; i++) {
            m.put(map[i][0], map[i][1]);
        }
        return post(url, m);
    }

    String post(String url, Map<String, String> map) throws IOException {
        HttpPost httpPost = new HttpPost(url);
        request = httpPost;
        List<NameValuePair> nvps = new ArrayList<>();
        for (String key : map.keySet()) {
            String value = map.get(key);
            nvps.add(new BasicNameValuePair(key, value));
        }
        httpPost.setEntity(new UrlEncodedFormEntity(nvps));
        CloseableHttpResponse response = httpclient.execute(httpPost, httpClientContext);
        Log.d(TAG, response.getStatusLine().toString());
        HttpEntity entity = response.getEntity();
        ContentType contentType = ContentType.getOrDefault(entity);
        String html = IOUtils.toString(entity.getContent(), contentType.getCharset());
        EntityUtils.consume(entity);
        response.close();
        request = null;
        return html;
    }

    public void error(final Throwable e) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (main.active(Search.this)) {
                    main.post(e);
                } else {
                    message.add(e.getMessage());
                    main.updateUnread();
                }
            }
        });
    }

    public void error(String msg) {
        if (main.active(this)) {
            main.post(msg);
        } else {
            message.add(msg);
            main.updateUnread();
        }
    }

    @Override
    public int getUnreadCount() {
        return message.size();
    }
}
