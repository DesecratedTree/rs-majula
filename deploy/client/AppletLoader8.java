import java.applet.Applet;
import java.applet.AppletContext;
import java.applet.AppletStub;
import java.applet.AudioClip;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * AppletLoader for Java 8 — the same job as AppletLoader.java, without the Java 9+ APIs
 * (InputStream.readAllBytes, AppletContext.getStreamKeys, pattern-matching instanceof), so the
 * 2012 client can run on a 2012-era JVM.
 *
 * Java 9 and later break this client: its socket code calls ProxySelector.select() and then
 * mutates the returned list, which is immutable on modern JDKs
 * (java.util.ImmutableCollections$AbstractImmutableCollection.addAll -> UnsupportedOperationException).
 *
 * Java 8 cannot run a .java file directly, so compile it once and run the class:
 *
 * <pre>
 * javac -d build/client deploy/client/AppletLoader8.java
 * java -Xmx512m -cp build/client AppletLoader8 http://127.0.0.1:8080/jav_config.ws
 * </pre>
 *
 * Arguments after the URL override params, e.g. {@code 18=2 8=2} for 742's world and lobby ids.
 */
public final class AppletLoader8 implements AppletStub, AppletContext {
    private final Map<String, String> config = new HashMap<String, String>();
    private final Map<String, String> params = new HashMap<String, String>();
    private Applet applet;

    public static void main(String[] args) throws Exception {
        // 742's client reads java.version as 1.x.y_zz; anything else opens the login screen's
        // Unsupported Java Warning (cs2 op 432 wants 1.6.0_10+).
        if (!System.getProperty("java.version", "").startsWith("1.")) {
            System.setProperty("java.version", "1.7.0_80");
        }
        String url = args.length > 0 ? args[0] : "http://127.0.0.1:8080/jav_config.ws";
        String[] overrides = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        new AppletLoader8().run(URI.create(url).toURL(), overrides);
    }

    private static void call(Object target, String name, Class<?> type, Object arg) {
        try {
            if (type == null) {
                target.getClass().getMethod(name).invoke(target);
            } else {
                target.getClass().getMethod(name, type).invoke(target, arg);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name, e);
        }
    }

    /** The applet a non-applet client draws into. */
    private static final class Host extends Applet {
        private static final long serialVersionUID = 1L;
        private final Object game;

        Host(Object game) {
            this.game = game;
        }

        @Override
        public void paint(Graphics g) {
            call(game, "paint", Graphics.class, g);
        }

        @Override
        public void update(Graphics g) {
            call(game, "update", Graphics.class, g);
        }
    }

    private static String read(URL url) throws IOException {
        InputStream in = url.openStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        } finally {
            in.close();
        }
    }

    private void run(URL configUrl, String[] overrides) throws Exception {
        String text = read(configUrl);
        for (String line : text.split("\r?\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (key.equals("param")) {
                put(value);
            } else {
                config.put(key, value);
            }
        }
        for (String override : overrides) {
            put(override);
        }
        URL jar = new URL(configUrl, "client.jar");
        URLClassLoader loader = new URLClassLoader(new URL[] { jar }, AppletLoader8.class.getClassLoader());
        String main = config.containsKey("initial_class") ? config.get("initial_class") : "client.class";
        Object game = loader.loadClass(main.replace(".class", "")).getDeclaredConstructor().newInstance();
        Applet applet = game instanceof Applet ? (Applet) game : new Host(game);
        this.applet = applet;
        applet.setStub(this);

        Frame frame = new Frame(config.containsKey("title") ? config.get("title") : "RuneScape");
        applet.setPreferredSize(new Dimension(
                Integer.parseInt(config.containsKey("applet_minwidth") ? config.get("applet_minwidth") : "1024"),
                Integer.parseInt(config.containsKey("applet_minheight") ? config.get("applet_minheight") : "768")));
        frame.add(applet);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.exit(0);
            }
        });
        frame.setVisible(true);
        if (game == applet) {
            applet.init();
            applet.start();
        } else {
            call(game, "supplyApplet", Applet.class, applet);
            call(game, "init", null, null);
            call(game, "start", null, null);
        }
    }

    private void put(String param) {
        int split = param.indexOf('=');
        if (split > 0) {
            params.put(param.substring(0, split), param.substring(split + 1));
        }
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public URL getDocumentBase() {
        return getCodeBase();
    }

    @Override
    public URL getCodeBase() {
        try {
            String base = config.containsKey("codebase") ? config.get("codebase") : "http://127.0.0.1/";
            return URI.create(base.endsWith("/") ? base : base + "/").toURL();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String getParameter(String name) {
        return params.get(name);
    }

    @Override
    public AppletContext getAppletContext() {
        return this;
    }

    @Override
    public void appletResize(int width, int height) {
    }

    @Override
    public AudioClip getAudioClip(URL url) {
        return null;
    }

    @Override
    public Image getImage(URL url) {
        return null;
    }

    @Override
    public Applet getApplet(String name) {
        return applet;
    }

    @Override
    public Enumeration<Applet> getApplets() {
        return Collections.enumeration(Collections.singletonList(applet));
    }

    @Override
    public void showDocument(URL url) {
    }

    @Override
    public void showDocument(URL url, String target) {
    }

    @Override
    public void showStatus(String status) {
    }

    @Override
    public void setStream(String key, InputStream stream) {
    }

    @Override
    public InputStream getStream(String key) {
        return null;
    }

    /**
     * Not in the Java 8 AppletContext, but some JDK 8 builds (and every 9+) declare it, so it is
     * present here without {@code @Override} and does no harm where the interface lacks it.
     */
    public java.util.Iterator<String> getStreamKeys() {
        return Collections.<String>emptyIterator();
    }
}
