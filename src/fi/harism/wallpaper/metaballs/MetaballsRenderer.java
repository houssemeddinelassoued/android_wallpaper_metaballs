/*
   Copyright 2012 Harri Smatt

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package fi.harism.wallpaper.metaballs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Vector;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * Renderer class.
 */
public final class MetaballsRenderer implements GLSurfaceView.Renderer {

	private final float[] mAspectRatio = new float[2];
	private final Vector<Blob> mBlobs = new Vector<Blob>();
	private final int mBlobsCount = 20;
	private ByteBuffer mBufferQuad;
	private Context mContext;
	private final MetaballsFbo mFbo = new MetaballsFbo();
	private final MetaballsShader mShaderBlob = new MetaballsShader();
	private final boolean[] mShaderCompilerSupport = new boolean[1];
	private final MetaballsShader mShaderCopy = new MetaballsShader();
	private int mWidth, mHeight;

	/**
	 * Default constructor.
	 */
	public MetaballsRenderer(Context context) {
		mContext = context;

		// Full view quad buffer.
		final byte[] QUAD = { -1, 1, -1, -1, 1, 1, 1, -1 };
		mBufferQuad = ByteBuffer.allocateDirect(8);
		mBufferQuad.put(QUAD).position(0);

		final float[] hsv = { 0f, 1f, 1f };
		for (int i = 0; i < mBlobsCount; ++i) {
			Blob blob = new Blob();

			hsv[0] = (float) (Math.random() * 360);
			int color = Color.HSVToColor(hsv);

			blob.mColor[0] = Color.red(color) / 255f;
			blob.mColor[1] = Color.green(color) / 255f;
			blob.mColor[2] = Color.blue(color) / 255f;
			mBlobs.add(blob);
		}
	}

	/**
	 * Loads String from raw resources with given id.
	 */
	private String loadRawString(int rawId) throws Exception {
		InputStream is = mContext.getResources().openRawResource(rawId);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int len;
		while ((len = is.read(buf)) != -1) {
			baos.write(buf, 0, len);
		}
		return baos.toString();
	}

	@Override
	public void onDrawFrame(GL10 unused) {

		// If shader compiler not supported return immediately.
		if (!mShaderCompilerSupport[0]) {
			// Clear view buffer.
			GLES20.glClearColor(0f, 0f, 0f, 1f);
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
			return;
		}

		// Disable unnecessary OpenGL flags.
		GLES20.glDisable(GLES20.GL_DEPTH_TEST);
		GLES20.glDisable(GLES20.GL_CULL_FACE);

		long time = SystemClock.uptimeMillis();
		for (Blob blob : mBlobs) {
			if (blob.mTimeTarget < time) {
				blob.mTimeSource = time;
				blob.mTimeTarget = time + 8000 + (long) (Math.random() * 4000);

				for (int i = 0; i < 2; ++i) {
					blob.mPositionSource[i] = blob.mPositionTarget[i];
					blob.mPositionTarget[i] = (float) (Math.random() * 2 - 1);
				}
			}
		}

		mShaderBlob.useProgram();
		int uModelViewM = mShaderBlob.getHandle("uModelViewM");
		int uColor = mShaderBlob.getHandle("uColor");
		int aPosition = mShaderBlob.getHandle("aPosition");

		GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_BYTE, false, 0,
				mBufferQuad);
		GLES20.glEnableVertexAttribArray(aPosition);

		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_DST_ALPHA);

		mFbo.bind();
		mFbo.bindTexture(0);

		// Clear view buffer.
		GLES20.glClearColor(0f, 0f, 0f, 1f);
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

		final Matrix matrix = new Matrix();
		final float[] matrixValues = new float[9];
		for (Blob blob : mBlobs) {
			float t = (float) (time - blob.mTimeSource)
					/ (blob.mTimeTarget - blob.mTimeSource);
			t = t * t * (3 - 2 * t);
			for (int i = 0; i < 2; ++i) {
				blob.mPosition[i] = blob.mPositionSource[i]
						+ (blob.mPositionTarget[i] - blob.mPositionSource[i])
						* t;
			}

			matrix.setScale(mAspectRatio[0] * 0.5f, mAspectRatio[1] * 0.5f);
			matrix.postTranslate(blob.mPosition[0], blob.mPosition[1]);
			matrix.getValues(matrixValues);
			GLES20.glUniformMatrix3fv(uModelViewM, 1, false, matrixValues, 0);

			GLES20.glUniform3f(uColor, blob.mColor[0], blob.mColor[1],
					blob.mColor[2]);

			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
		}

		GLES20.glDisable(GLES20.GL_BLEND);

		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
		GLES20.glViewport(0, 0, mWidth, mHeight);

		mShaderCopy.useProgram();
		aPosition = mShaderCopy.getHandle("aPosition");

		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mFbo.getTexture(0));

		GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_BYTE, false, 0,
				mBufferQuad);
		GLES20.glEnableVertexAttribArray(aPosition);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
	}

	@Override
	public void onSurfaceChanged(GL10 unused, int width, int height) {
		mWidth = width;
		mHeight = height;

		GLES20.glViewport(0, 0, mWidth, mHeight);

		// Store view aspect ratio.
		mAspectRatio[0] = 1f / ((float) Math.max(mWidth, mHeight) / mHeight);
		mAspectRatio[1] = 1f / ((float) Math.max(mWidth, mHeight) / mWidth);

		mFbo.init(mWidth, mHeight, 1);
	}

	@Override
	public void onSurfaceCreated(GL10 unused, EGLConfig config) {
		// Check if shader compiler is supported.
		GLES20.glGetBooleanv(GLES20.GL_SHADER_COMPILER, mShaderCompilerSupport,
				0);

		// If not, show user an error message and return immediately.
		if (mShaderCompilerSupport[0] == false) {
			String msg = mContext.getString(R.string.error_shader_compiler);
			showError(msg);
			return;
		}

		// Load vertex and fragment shaders.
		try {
			String vertexSource, fragmentSource;
			vertexSource = loadRawString(R.raw.blob_vs);
			fragmentSource = loadRawString(R.raw.blob_fs);
			mShaderBlob.setProgram(vertexSource, fragmentSource);
			vertexSource = loadRawString(R.raw.copy_vs);
			fragmentSource = loadRawString(R.raw.copy_fs);
			mShaderCopy.setProgram(vertexSource, fragmentSource);
		} catch (Exception ex) {
			showError(ex.getMessage());
		}
	}

	/**
	 * Shows Toast on screen with given message.
	 */
	private void showError(final String errorMsg) {
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(mContext, errorMsg, Toast.LENGTH_LONG).show();
			}
		});
	}

	private class Blob {
		public final float[] mColor = new float[3];
		public final float[] mPosition = new float[2];
		public final float[] mPositionSource = new float[2];
		public final float[] mPositionTarget = new float[2];
		public long mTimeSource;
		public long mTimeTarget;
	}

}
