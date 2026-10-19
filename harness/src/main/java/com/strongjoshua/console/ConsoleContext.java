/*
 * Copyright 2015 StrongJoshua (https://github.com/StrongJoshua/libgdx-inGameConsole)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.strongjoshua.console;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.reflect.ClassReflection;

/**
 * Copy of sjInGameConsole 1.0.1's ConsoleContext (Apache License 2.0), recompiled against libGDX 1.14.2. The released
 * library was built against libGDX 1.12, where Label.getText() returns a StringBuilder; in 1.14 it returns a CharArray,
 * so the original class throws NoSuchMethodError when the Copy action runs, and TeaVM refuses to compile the call.
 * The class body is unchanged. The harness build leaves the original class out of the library jar (see
 * harness/build.gradle), so this one is the only copy on every platform. Delete this file, and that filter, when a
 * console release built for libGDX 1.14 exists.
 */
public class ConsoleContext {
	private Table root;
	private Label label, copy;
	private InputListener stageListener;

	ConsoleContext (Class<? extends Table> tableClass, Class<? extends Label> labelClass, Skin skin, String background) {
		try {
			root = ClassReflection.newInstance(tableClass);
		} catch (Exception e) {
			throw new RuntimeException("Table class does not support empty constructor.");
		}
		try {
			copy = (Label) ClassReflection.getConstructor(labelClass, CharSequence.class, Skin.class)
					.newInstance("Copy", skin);
		} catch (Exception e) {
			try {
				copy = (Label) ClassReflection.getConstructor(labelClass, CharSequence.class)
						.newInstance("Copy");
			} catch (Exception e2) {
				throw new RuntimeException(
					"Label class does not support either (<CharSequence>, <Skin>) or (<CharSequence>) constructors.");
			}
		}
		copy.addListener(new ClickListener() {
			@Override public void clicked (InputEvent event, float x, float y) {
				if (event.getPointer() != 0)
					return;
				if (label == null)
					throw new RuntimeException("Trying to copy a null label (this should never happen).");
				Gdx.app.getClipboard().setContents(label.getText().toString().trim());
				ConsoleContext.this.remove();
			}
		});
		copy.addListener(new HoverListener(copy));

		root.add(copy);
		root.pad(5);
		root.setBackground(skin.getDrawable(background));
		root.setSize(root.getPrefWidth(), root.getPrefHeight());

		stageListener = new InputListener() {
			@Override public boolean touchDown (InputEvent event, float x, float y, int pointer, int button) {
				if (ConsoleContext.this.root.hit(x, y, false) == null)
					remove();
				return true;
			}
		};
	}

	void setLabel (Label l) {
		label = l;
	}

	protected void setStage (Stage stage) {
		if (stage != null) {
			stage.addListener(stageListener);
			stage.addActor(root);
		}
	}

	protected boolean remove () {
		if (root.getStage() != null)
			root.getStage().removeListener(stageListener);
		return root.remove();
	}

	protected void setPosition (float x, float y) {
		root.setPosition(x, y);
	}

	private class HoverListener extends InputListener {
		private Label label;

		HoverListener (Label l) {
			this.label = l;
		}

		@Override public void enter (InputEvent event, float x, float y, int pointer, Actor fromActor) {
			label.setColor(Color.CYAN);
		}

		@Override public void exit (InputEvent event, float x, float y, int pointer, Actor toActor) {
			label.setColor(Color.WHITE);
		}
	}
}
