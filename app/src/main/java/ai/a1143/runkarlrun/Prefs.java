package ai.a1143.runkarlrun;

import android.content.Context;
import android.content.SharedPreferences;

/** The six Fat-Cats and the one setting this app has. */
public final class Prefs {

    public static final String[] CHARACTERS = {"black", "white", "grey", "blue", "orange", "calico"};
    public static final String DEFAULT_CHARACTER = "orange";

    private static final String FILE = "runkarlrun";
    private static final String KEY_CHARACTER = "character";

    private Prefs() {
    }

    public static String getCharacter(Context c) {
        String v = prefs(c).getString(KEY_CHARACTER, DEFAULT_CHARACTER);
        for (String key : CHARACTERS) {
            if (key.equals(v)) return key;
        }
        return DEFAULT_CHARACTER;
    }

    public static void setCharacter(Context c, String key) {
        prefs(c).edit().putString(KEY_CHARACTER, key).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int nameRes(String key) {
        switch (key) {
            case "black":
                return R.string.cat_black_name;
            case "white":
                return R.string.cat_white_name;
            case "grey":
                return R.string.cat_grey_name;
            case "blue":
                return R.string.cat_blue_name;
            case "calico":
                return R.string.cat_calico_name;
            case "orange":
            default:
                return R.string.cat_orange_name;
        }
    }

    public static int eyesRes(String key) {
        switch (key) {
            case "black":
                return R.string.cat_black_eyes;
            case "white":
                return R.string.cat_white_eyes;
            case "grey":
                return R.string.cat_grey_eyes;
            case "blue":
                return R.string.cat_blue_eyes;
            case "calico":
                return R.string.cat_calico_eyes;
            case "orange":
            default:
                return R.string.cat_orange_eyes;
        }
    }

    public static int imageRes(String key) {
        switch (key) {
            case "black":
                return R.drawable.fatcat_black;
            case "white":
                return R.drawable.fatcat_white;
            case "grey":
                return R.drawable.fatcat_grey;
            case "blue":
                return R.drawable.fatcat_blue;
            case "calico":
                return R.drawable.fatcat_calico;
            case "orange":
            default:
                return R.drawable.fatcat_orange;
        }
    }
}
