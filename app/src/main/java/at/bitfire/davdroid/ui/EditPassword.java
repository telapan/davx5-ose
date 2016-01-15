/*
 * Copyright © 2013 – 2016 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid.ui;

import android.content.Context;
import android.support.v7.widget.AppCompatCheckBox;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;

import at.bitfire.davdroid.R;

public class EditPassword extends LinearLayout {

    public EditPassword(Context context) {
        super(context, null);
    }

    public EditPassword(Context context, AttributeSet attrs) {
        super(context, attrs);

        inflate(context, R.layout.edit_password, this);

        final EditText editPassword = (EditText)findViewById(R.id.password);

        AppCompatCheckBox checkShowPassword = (AppCompatCheckBox)findViewById(R.id.show_password);
        checkShowPassword.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                int inputType = editPassword.getInputType() & ~EditorInfo.TYPE_MASK_VARIATION;
                inputType |= isChecked ? EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : EditorInfo.TYPE_TEXT_VARIATION_PASSWORD;
                editPassword.setInputType(inputType);
            }
        });
    }

}