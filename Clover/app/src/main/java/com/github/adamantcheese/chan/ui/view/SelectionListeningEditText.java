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
package com.github.adamantcheese.chan.ui.view;

import android.content.Context;
import android.util.AttributeSet;

public class SelectionListeningEditText extends androidx.appcompat.widget.AppCompatEditText {
    private SelectionChangedListener listener;

    public SelectionListeningEditText(Context context) {
        super(context);
    }

    public SelectionListeningEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SelectionListeningEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setSelectionChangedListener(SelectionChangedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);

        if (listener != null) {
            listener.onSelectionChanged();
        }
    }

    public interface SelectionChangedListener {
        void onSelectionChanged();
    }
}
