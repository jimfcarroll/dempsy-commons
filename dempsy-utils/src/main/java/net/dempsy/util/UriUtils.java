package net.dempsy.util;

import static net.dempsy.util.Functional.uncheck;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UriUtils {

    public static final String SEP = "/";
    public static final char SEP_CHAR = '/';

    /**
     * Parse the query string into a multi-map. Edge cases:
     *
     * <ul>
     * <li>1. The same key appears twice. This will result in a multimap that has
     * multiple entries for the same key.</li>
     * <li>2. The query string has an entry with no equals. This will result in an
     * entry in the multimap for the value as the key but with a null in the list.
     * For example: {@code ...?tcp&loop=true}</li>
     * <li>3. The query string has an entry that has an equal sign but no value. This
     * will result in an entry in the multimap where the value is assumed to be an
     * empty string. For example: {@code ...?loop&something=somethingelse}</li>
     * <li>4. The query string has an entry that starts with an equals sign. This
     * will result in a value added to a key where the key is an empty string.
     * For example: {@code ...?=true}</li>
     * </ul>
     */
    public static Map<String, List<String>> query(final URI toUse) {
        final Map<String, List<String>> queryPairs = new LinkedHashMap<>();

        final String query = toUse.getQuery();
        if(query != null) {
            final String[] pairs = query.split("&");
            for(final String pair: pairs) {
                if(pair.length() != 0) {
                    final int idx = pair.indexOf("=");
                    if(idx < 0) // there's no equal so it's all key
                        add(queryPairs, uncheck(() -> URLDecoder.decode(pair, "UTF-8")), null);
                    else if(idx == 0) // equals is first?
                        add(queryPairs, "", pair.substring(1));
                    else
                        add(queryPairs, uncheck(() -> URLDecoder.decode(pair.substring(0, idx), "UTF-8")),
                            uncheck(() -> URLDecoder.decode(pair.substring(idx + 1), "UTF-8")));
                }
            }
        }
        return queryPairs;
    }

    public static URI removeQuery(final URI uri) {
        return uncheck(() -> new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), uri.getPath(), null, uri.getFragment()));
    }

    public static String urlEncode(final String val) {
        return uncheck(() -> URLEncoder.encode(val, StandardCharsets.UTF_8.name()));
    }

    public static URI resolve(final URI base, final String child) {
        final String path = base.getPath();
        final String childStripped = child.startsWith(SEP) ? child.substring(1) : child;
        final String newpath = path.endsWith(SEP) ? (path + childStripped) : (path + SEP + childStripped);
        try {
            return new URI(base.getScheme(), base.getAuthority(), newpath, base.getQuery(), base.getFragment());
        } catch(final URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static URI resolve(final URI base, final String child, final String enc) {
        final String path = base.getPath();
        final String childStripped = child.startsWith(SEP) ? child.substring(1) : child;
        final String parentPath = path.endsWith(SEP) ? path.substring(0, path.length() - 1) : path;
        final String newpath = parentPath + enc + childStripped;
        try {
            return new URI(base.getScheme(), base.getAuthority(), newpath, base.getQuery(), base.getFragment());
        } catch(final URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getParent(final String pathPartOnly) {
        // is this a URI?
        final int prefixLength = pathPartOnly.startsWith("/") ? 1 : 0;
        final int index = pathPartOnly.lastIndexOf(SEP_CHAR);
        if(index < prefixLength) {
            if((prefixLength > 0) && (pathPartOnly.length() > prefixLength))
                return pathPartOnly.substring(0, prefixLength);
            return null;
        }
        return pathPartOnly.substring(0, index);
    }

    /**
     * If the return value of {@link UriUtils#getParent(String)} is passed to this, it
     * will return true if the path represents the root of the file system tree.
     * In this case it will be true if the path is '/' or null.
     */
    public static boolean isRoot(final String path) {
        return(path == null || SEP.equals(path));
    }

    public static URI getParent(final URI uri) {
        final String newpath = getParent(uri.getPath());
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), newpath, uri.getQuery(), uri.getFragment());
        } catch(final URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * If the uri has no scheme, it assumes it's a file.
     */
    public static URI sanitize(final String uri) throws URISyntaxException {
        final URI potentialReturn = new URI(uri);

        if(potentialReturn.getScheme() == null) { // here, we assume it's a file.
            return new File(uri).toURI();
        }

        return potentialReturn;
    }

    /**
     * Used mostly for debugging.
     */
    public static void dumpUri(final URI uri) {
        System.out.println("scheme:" + uri.getScheme());
        System.out.println("scheme specific part:" + uri.getRawSchemeSpecificPart());
        System.out.println("authority:" + uri.getRawAuthority());
        System.out.println("fragment:" + uri.getRawFragment());
        System.out.println("host:" + uri.getHost());
        System.out.println("path:" + uri.getRawPath());
        System.out.println("port" + uri.getPort());
        System.out.println("query:" + uri.getRawQuery());
    }

    private static void add(final Map<String, List<String>> map, final String key, final String value) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
}
