/*
 * Kuroba - *chan browser https://github.com/Adamantcheese/Kuroba/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.adamantcheese.chan.ui.captcha.v2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;

import com.github.adamantcheese.chan.core.di.NetModule;
import com.github.adamantcheese.chan.utils.BackgroundUtils;
import com.github.adamantcheese.chan.utils.IOUtils;
import com.github.adamantcheese.chan.utils.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

import static com.github.adamantcheese.chan.ui.captcha.v2.CaptchaInfo.CaptchaType.UNKNOWN;

public class CaptchaNoJsHtmlParser {
    private static final String googleBaseUrl = "https://www.google.com";
    private static final Pattern checkboxesPattern = Pattern.compile(
            "<input class=\"fbc-imageselect-checkbox-\\d+\" type=\"checkbox\" name=\"response\" value=\"(\\d+)\">");

    // FIXME: this pattern captures the C parameter as many times as it is in the HTML.
    // Should match only the first occurrence instead.
    private static final Pattern cParameterPattern =
            Pattern.compile("<input type=\"hidden\" name=\"c\" value=\"(.*?)\"/>");
    private static final Pattern challengeTitlePattern =
            Pattern.compile("<div class=\"(rc-imageselect-desc-no-canonical|rc-imageselect-desc)\">(.*?)</div>");
    private static final Pattern challengeImageUrlPattern =
            Pattern.compile("<img class=\"fbc-imageselect-payload\" src=\"(.*?)&");
    private static final Pattern challengeTitleBoldPartPattern = Pattern.compile("<strong>(.*?)</strong>");
    private static final Pattern verificationTokenPattern = Pattern.compile(
            "<div class=\"fbc-verification-token\"><textarea dir=\"ltr\" readonly>(.*?)</textarea></div>");
    private static final String CHALLENGE_IMAGE_FILE_NAME = "challenge_image_file";

    private final NetModule.OkHttpClientWithUtils okHttpClient;
    private final Context context;

    public CaptchaNoJsHtmlParser(Context context, NetModule.OkHttpClientWithUtils okHttpClient) {
        this.context = context;
        this.okHttpClient = okHttpClient;
    }

    @NonNull
    public CaptchaInfo parseHtml(String responseHtml, String siteKey)
            throws CaptchaNoJsV2ParsingError, IOException {
        BackgroundUtils.ensureBackgroundThread();

        CaptchaInfo captchaInfo = new CaptchaInfo();

        // parse challenge checkboxes' ids
        parseCheckboxes(responseHtml, captchaInfo);

        // parse captcha random key
        parseCParameter(responseHtml, captchaInfo);

        // parse title
        parseChallengeTitle(responseHtml, captchaInfo);

        // parse image url, download image and split it into list of separate images
        parseAndDownloadChallengeImage(responseHtml, captchaInfo, siteKey);

        return captchaInfo;
    }

    @NonNull
    String parseVerificationToken(String responseHtml)
            throws CaptchaNoJsV2ParsingError {
        BackgroundUtils.ensureBackgroundThread();

        Matcher matcher = verificationTokenPattern.matcher(responseHtml);
        if (!matcher.find()) {
            throw new CaptchaNoJsV2ParsingError("Could not parse verification token");
        }

        String token;

        try {
            token = matcher.group(1);
        } catch (Throwable error) {
            Logger.e(this, "Could not parse verification token", error);
            throw error;
        }

        if (TextUtils.isEmpty(token)) {
            throw new CaptchaNoJsV2ParsingError("Verification token is null or empty");
        }

        return token;
    }

    private void parseChallengeTitle(String responseHtml, CaptchaInfo captchaInfo)
            throws CaptchaNoJsV2ParsingError {
        Matcher matcher = challengeTitlePattern.matcher(responseHtml);
        if (!matcher.find()) {
            throw new CaptchaNoJsV2ParsingError("Could not parse challenge title " + responseHtml);
        }

        SpannableString captchaTitle;

        try {
            String title = matcher.group(2).replace("Select all images", "Tap all");
            Matcher titleMatcher = challengeTitleBoldPartPattern.matcher(title);

            if (titleMatcher.find()) {
                // find the part of the title that should be bold
                int start = title.indexOf("<strong>");

                String firstPart = title.substring(0, start);
                String boldPart = titleMatcher.group(1);
                String resultTitle = firstPart + boldPart;

                captchaTitle = new SpannableString(resultTitle);
                captchaTitle.setSpan(new StyleSpan(Typeface.BOLD),
                        firstPart.length(),
                        firstPart.length() + boldPart.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            } else {
                // could not find it
                captchaTitle = new SpannableString(title);
            }
        } catch (Throwable error) {
            Logger.e(this, "Error while trying to parse challenge title", error);
            throw error;
        }

        if (captchaTitle.length() == 0) {
            throw new CaptchaNoJsV2ParsingError("challengeTitle is null or empty");
        }

        captchaInfo.captchaTitle = captchaTitle;
    }

    private void parseAndDownloadChallengeImage(String responseHtml, CaptchaInfo captchaInfo, String siteKey)
            throws CaptchaNoJsV2ParsingError, IOException {
        Matcher matcher = challengeImageUrlPattern.matcher(responseHtml);
        if (!matcher.find()) {
            throw new CaptchaNoJsV2ParsingError("Could not parse challenge image url");
        }

        String challengeImageUrl;

        try {
            challengeImageUrl = matcher.group(1);
        } catch (Throwable error) {
            Logger.e(this, "Error while trying to parse challenge image url", error);
            throw error;
        }

        if (challengeImageUrl == null) {
            throw new CaptchaNoJsV2ParsingError("challengeImageUrl is null");
        }

        if (challengeImageUrl.isEmpty()) {
            throw new CaptchaNoJsV2ParsingError("challengeImageUrl is empty");
        }

        downloadAndStoreImage(googleBaseUrl + challengeImageUrl + "&k=" + siteKey);

        captchaInfo.challengeImages = decodeImagesFromFile(getChallengeImageFile(),
                captchaInfo.captchaType.columnCount,
                captchaInfo.captchaType.rowCount
        );
    }

    private void parseCParameter(String responseHtml, CaptchaInfo captchaInfo)
            throws CaptchaNoJsV2ParsingError {
        Matcher matcher = cParameterPattern.matcher(responseHtml);
        if (!matcher.find()) {
            throw new CaptchaNoJsV2ParsingError("Could not parse c parameter");
        }

        String cParameter;

        try {
            cParameter = matcher.group(1);
        } catch (Throwable error) {
            Logger.e(this, "Error while trying to parse c parameter", error);
            throw error;
        }

        if (cParameter == null) {
            throw new CaptchaNoJsV2ParsingError("cParameter is null");
        }

        if (cParameter.isEmpty()) {
            throw new CaptchaNoJsV2ParsingError("cParameter is empty");
        }

        captchaInfo.cParameter = cParameter;
    }

    private void parseCheckboxes(String responseHtml, CaptchaInfo captchaInfo)
            throws CaptchaNoJsV2ParsingError {
        Matcher matcher = checkboxesPattern.matcher(responseHtml);
        Set<Integer> checkboxesSet = new HashSet<>(matcher.groupCount());
        int index = 0;

        while (matcher.find()) {
            try {
                Integer checkboxId = Integer.parseInt(matcher.group(1));
                checkboxesSet.add(checkboxId);
            } catch (Throwable error) {
                Logger.e(this, "Error while trying to parse checkbox with id (" + index + ")", error);
                throw error;
            }

            ++index;
        }

        if (checkboxesSet.isEmpty()) {
            throw new CaptchaNoJsV2ParsingError("Could not parse any checkboxes!");
        }

        CaptchaInfo.CaptchaType captchaType;

        try {
            captchaType = CaptchaInfo.CaptchaType.fromCheckboxesCount(checkboxesSet.size());
        } catch (Throwable error) {
            Logger.e(this, "Error while trying to parse captcha type", error);
            throw error;
        }

        if (captchaType == UNKNOWN) {
            throw new CaptchaNoJsV2ParsingError("Unknown captcha type");
        }

        captchaInfo.captchaType = captchaType;
        captchaInfo.checkboxes = new ArrayList<>(checkboxesSet);
    }

    private void downloadAndStoreImage(String fullUrl)
            throws IOException, CaptchaNoJsV2ParsingError {
        Request request = new Request.Builder().url(fullUrl).build();

        try (Response response = okHttpClient.getProxiedClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new CaptchaNoJsV2ParsingError(
                        "Could not download challenge image, status code = " + response.code());
            }

            IOUtils.writeToFile(response.body().byteStream(), getChallengeImageFile(), -1);
        }
    }

    private File getChallengeImageFile()
            throws IOException {
        File imageFile = new File(context.getCacheDir(), CHALLENGE_IMAGE_FILE_NAME);

        if (!imageFile.exists()) {
            imageFile.createNewFile();
        }

        return imageFile;
    }

    private List<Bitmap> decodeImagesFromFile(File challengeImageFile, int columns, int rows) {
        Bitmap originalBitmap = BitmapFactory.decodeFile(challengeImageFile.getAbsolutePath());
        List<Bitmap> resultImages = new ArrayList<>(columns * rows);

        try {
            int imageWidth = originalBitmap.getWidth() / columns;
            int imageHeight = originalBitmap.getHeight() / rows;

            for (int column = 0; column < columns; ++column) {
                for (int row = 0; row < rows; ++row) {
                    Bitmap imagePiece = Bitmap.createBitmap(originalBitmap,
                            row * imageWidth,
                            column * imageHeight,
                            imageWidth,
                            imageHeight
                    );

                    resultImages.add(imagePiece);
                }
            }

            return resultImages;
        } catch (Throwable error) {
            for (Bitmap bitmap : resultImages) {
                if (!bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }

            resultImages.clear();
            throw error;
        } finally {
            if (originalBitmap != null) {
                originalBitmap.recycle();
            }
        }
    }

    public static class CaptchaNoJsV2ParsingError
            extends Exception {
        public CaptchaNoJsV2ParsingError(String message) {
            super(message);
        }
    }
}
