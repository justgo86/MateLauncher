/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mobi.intuitit.android.mate.launcher;

import java.lang.reflect.Method;
import java.util.ArrayList;

import mobi.intuitit.android.widget.WidgetCellLayout;
import mobi.intuitit.android.widget.WidgetSpace;
import android.app.Activity;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetHostView;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Scroller;
import android.widget.TextView;

/**
 * The workspace is a wide area with a wallpaper and a finite number of screens.
 * Each screen contains a number of icons, folders or widgets the user can
 * interact with. A workspace is meant to be used with a fixed width only.
 */
public class Workspace extends WidgetSpace implements DropTarget, DragSource,
		DragScroller {
	private static final int INVALID_SCREEN = -1;

	/**
	 * The velocity at which a fling gesture will cause us to snap to the next
	 * screen
	 */
	private static final int SNAP_VELOCITY = 1000;

	private int mDefaultScreen;

	private final WallpaperManager mWallpaperManager;

	private boolean mFirstLayout = true;

	private int mNextScreen = INVALID_SCREEN;
	private Scroller mScroller;
	private VelocityTracker mVelocityTracker;

	/**
	 * CellInfo for the cell that is currently being dragged
	 */
	private LayoutType.CellInfo mDragInfo;

	/**
	 * Target drop area calculated during last acceptDrop call.
	 */
	private int[] mTargetCell = null;

	private float mLastMotionX;
	private float mLastMotionY;

	private final static int TOUCH_STATE_REST = 0;
	private final static int TOUCH_STATE_SCROLLING = 1;

	private int mTouchState = TOUCH_STATE_REST;

	private OnLongClickListener mLongClickListener;

	private Launcher mLauncher;
	private DragController mDragger;

	/**
	 * Cache of vacant cells, used during drag events and invalidated as needed.
	 */
	private LayoutType.CellInfo mVacantCache = null;

	private int[] mTempCell = new int[2];
	private int[] mTempEstimate = new int[2];

	private boolean mLocked;

	private int mTouchSlop;
	private int mMaximumVelocity;

	final Rect mDrawerBounds = new Rect();
	final Rect mClipBounds = new Rect();
	int mDrawerContentHeight;
	int mDrawerContentWidth;

	/**
	 * Used to inflate the Workspace from XML.
	 * 
	 * @param context
	 *            The application's context.
	 * @param attrs
	 *            The attribtues set containing the Workspace's customization
	 *            values.
	 */
	public Workspace(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	/**
	 * Used to inflate the Workspace from XML.
	 * 
	 * @param context
	 *            The application's context.
	 * @param attrs
	 *            The attribtues set containing the Workspace's customization
	 *            values.
	 * @param defStyle
	 *            Unused.
	 */
	public Workspace(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);

		mWallpaperManager = WallpaperManager.getInstance(context);

		// Try to set default screen from preferences
		try {
			String d = PreferenceManager.getDefaultSharedPreferences(context)
					.getString(context.getString(R.string.key_default_screen),
							"2");
			mDefaultScreen = Integer.parseInt(d) - 1;
		} catch (Exception e) {
			TypedArray a = context.obtainStyledAttributes(attrs,
					R.styleable.Workspace, defStyle, 0);
			mDefaultScreen = a.getInt(R.styleable.Workspace_defaultScreen, 1);
			a.recycle();
		}

		initWorkspace();
	}

	/**
	 * Initializes various states for this workspace.
	 */
	private void initWorkspace() {
		mScroller = new Scroller(getContext());
		mCurrentScreen = mDefaultScreen;
		Launcher.setScreen(mCurrentScreen);

		final ViewConfiguration configuration = ViewConfiguration
				.get(getContext());
		mTouchSlop = configuration.getScaledTouchSlop();
		mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
	}

	@Override
	public void addView(View child, int index, LayoutParams params) {
		if (!(child instanceof LayoutType)) {
			throw new IllegalArgumentException(
					"A Workspace can only have CellLayout children.");
		}

		super.addView(child, index, params);
	}

	@Override
	public void addView(View child) {
		if (!(child instanceof LayoutType)) {
			throw new IllegalArgumentException(
					"A Workspace can only have CellLayout children.");
		}

		super.addView(child);
	}

	@Override
	public void addView(View child, int index) {
		if (!(child instanceof LayoutType)) {
			throw new IllegalArgumentException(
					"A Workspace can only have CellLayout children.");
		}

		super.addView(child, index);
	}

	@Override
	public void addView(View child, int width, int height) {
		// if (!(child instanceof LayoutType)) {
		// throw new IllegalArgumentException(
		// "A Workspace can only have CellLayout children.");
		// }

		super.addView(child, width, height);
	}

	@Override
	public void addView(View child, LayoutParams params) {
		if (!(child instanceof LayoutType)) {
			throw new IllegalArgumentException(
					"A Workspace can only have CellLayout children.");
		}
		super.addView(child, params);
	}

	/**
	 * @return The open folder on the current screen, or null if there is none
	 */
	Folder getOpenFolder() {
		LayoutType currentScreen = (LayoutType) getChildAt(mCurrentScreen);
		int count = currentScreen.getChildCount();
		for (int i = 0; i < count; i++) {
			View child = currentScreen.getChildAt(i);

			// -- MLayout
			if (currentScreen instanceof CellLayout) {
				LayoutType.LayoutParams lp = (LayoutType.LayoutParams) child
						.getLayoutParams();
				if (lp.cellHSpan == 4 && lp.cellVSpan == 4
						&& child instanceof Folder) {
					return (Folder) child;
				}
			} else {
				if (child instanceof Folder)
					return (Folder) child;
			}
		}
		return null;
	}

	ArrayList<Folder> getOpenFolders() {
		final int screens = getChildCount();
		ArrayList<Folder> folders = new ArrayList<Folder>(screens);

		for (int screen = 0; screen < screens; screen++) {
			LayoutType currentScreen = (LayoutType) getChildAt(screen);
			int count = currentScreen.getChildCount();
			for (int i = 0; i < count; i++) {
				View child = currentScreen.getChildAt(i);

				// -- MLayout
				if (currentScreen instanceof CellLayout) {
					LayoutType.LayoutParams lp = (LayoutType.LayoutParams) child
							.getLayoutParams();
					if (lp.cellHSpan == 4 && lp.cellVSpan == 4
							&& child instanceof Folder) {
						folders.add((Folder) child);
						break;
					} else {
						if (child instanceof Folder) {
							folders.add((Folder) child);
							break;
						}
					}
				}
			}
		}

		return folders;
	}

	void resetDefaultScreen(int screen) {
		if (screen >= getChildCount() || screen < 0) {
			Log.e("H++ Workspace", "Cannot reset default screen to " + screen);
			return;
		}
		mDefaultScreen = screen;
	}

	boolean isDefaultScreenShowing() {
		return mCurrentScreen == mDefaultScreen;
	}

	/**
	 * Returns the index of the currently displayed screen.
	 * 
	 * @return The index of the currently displayed screen.
	 */
	int getCurrentScreen() {
		return mCurrentScreen;
	}

	/**
	 * Sets the current screen.
	 * 
	 * @param currentScreen
	 */
	void setCurrentScreen(int currentScreen) {
		clearVacantCache();
		mCurrentScreen = Math.max(0,
				Math.min(currentScreen, getChildCount() - 1));
		scrollTo(mCurrentScreen * getWidth(), 0);
		invalidate();
	}

	/**
	 * Adds the specified child in the current screen. The position and
	 * dimension of the child are defined by x, y, spanX and spanY.
	 * 
	 * @param child
	 *            The child to add in one of the workspace's screens.
	 * @param x
	 *            The X position of the child in the screen's grid.
	 * @param y
	 *            The Y position of the child in the screen's grid.
	 * @param spanX
	 *            The number of cells spanned horizontally by the child.
	 * @param spanY
	 *            The number of cells spanned vertically by the child.
	 */
	void addInCurrentScreen(View child, int x, int y, int spanX, int spanY) {
		addInScreen(child, mCurrentScreen, x, y, spanX, spanY, false);
	}

	/**
	 * Adds the specified child in the current screen. The position and
	 * dimension of the child are defined by x, y, spanX and spanY.
	 * 
	 * @param child
	 *            The child to add in one of the workspace's screens.
	 * @param x
	 *            The X position of the child in the screen's grid.
	 * @param y
	 *            The Y position of the child in the screen's grid.
	 * @param spanX
	 *            The number of cells spanned horizontally by the child.
	 * @param spanY
	 *            The number of cells spanned vertically by the child.
	 * @param insert
	 *            When true, the child is inserted at the beginning of the
	 *            children list.
	 */
	void addInCurrentScreen(View child, int x, int y, int spanX, int spanY,
			boolean insert) {
		addInScreen(child, mCurrentScreen, x, y, spanX, spanY, insert);
	}

	/**
	 * Adds the specified child in the specified screen. The position and
	 * dimension of the child are defined by x, y, spanX and spanY.
	 * 
	 * @param child
	 *            The child to add in one of the workspace's screens.
	 * @param screen
	 *            The screen in which to add the child.
	 * @param x
	 *            The X position of the child in the screen's grid.
	 * @param y
	 *            The Y position of the child in the screen's grid.
	 * @param spanX
	 *            The number of cells spanned horizontally by the child.
	 * @param spanY
	 *            The number of cells spanned vertically by the child.
	 */
	void addInScreen(View child, int screen, int x, int y, int spanX, int spanY) {
		addInScreen(child, screen, x, y, spanX, spanY, false);
	}

	/**
	 * Adds the specified child in the specified screen. The position and
	 * dimension of the child are defined by x, y, spanX and spanY.
	 * 
	 * @param child
	 *            The child to add in one of the workspace's screens.
	 * @param screen
	 *            The screen in which to add the child.
	 * @param x
	 *            The X position of the child in the screen's grid.
	 * @param y
	 *            The Y position of the child in the screen's grid.
	 * @param spanX
	 *            The number of cells spanned horizontally by the child.
	 * @param spanY
	 *            The number of cells spanned vertically by the child.
	 * @param insert
	 *            When true, the child is inserted at the beginning of the
	 *            children list.
	 */
	void addInScreen(View child, int screen, int x, int y, int spanX,
			int spanY, boolean insert) {
		if (screen < 0 || screen >= getChildCount()) {
			Log.e(Launcher.LOG_TAG, "The screen must be >= 0 and < "
					+ getChildCount() + ". Now you are querying " + screen);
			return;
			// throw new IllegalStateException("The screen must be >= 0 and < "
			// + getChildCount());
		}

		clearVacantCache();

		final LayoutType group = (LayoutType) getChildAt(screen);
		LayoutType.LayoutParams lp = (LayoutType.LayoutParams) child
				.getLayoutParams();
		if (lp == null) {
			lp = new LayoutType.LayoutParams(x, y, spanX, spanY);
		} else {			
			lp.cellX = x;
			lp.cellY = y;
			
			// -- MLayout
			if (group instanceof CellLayout) {
				lp.cellHSpan = spanX;
				lp.cellVSpan = spanY;
			}
		}

		if(child instanceof Folder) {
			lp.width = 100;
			lp.height = 100;
		}
		
		group.addView(child, insert ? 0 : -1, lp);
		if (!(child instanceof Folder)) { // / -
			child.setOnLongClickListener(mLongClickListener);
		}
	}

	void addWidget(View view, Widget widget, boolean insert) {
		addInScreen(view, widget.screen, widget.cellX, widget.cellY,
				widget.spanX, widget.spanY, insert);
	}

	LayoutType.CellInfo findAllVacantCells(boolean[] occupied) {
		LayoutType group = (LayoutType) getChildAt(mCurrentScreen);
		if (group != null) {
			return group.findAllVacantCells(occupied, null);
		}
		return null;
	}

	LayoutType.CellInfo findAllVacantCellsFromModel() {
		LayoutType group = (LayoutType) getChildAt(mCurrentScreen);
		if (group != null) {
			int countX = group.getCountX();
			int countY = group.getCountY();
			boolean occupied[][] = new boolean[countX][countY];
			Launcher.getModel().findAllOccupiedCells(occupied, countX, countY,
					mCurrentScreen);
			return group.findAllVacantCellsFromOccupied(occupied, countX,
					countY);
		}
		return null;
	}

	private void clearVacantCache() {
		if (mVacantCache != null) {
			mVacantCache.clearVacantCells();
			mVacantCache = null;
		}
	}

	/**
	 * Registers the specified listener on each screen contained in this
	 * workspace.
	 * 
	 * @param l
	 *            The listener used to respond to long clicks.
	 */
	@Override
	public void setOnLongClickListener(OnLongClickListener l) {
		mLongClickListener = l;
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			getChildAt(i).setOnLongClickListener(l);
		}
	}

	private void updateWallpaperOffset() {
		updateWallpaperOffset(getChildAt(getChildCount() - 1).getRight()
				- (getRight() - getLeft()));
	}

	private void updateWallpaperOffset(int scrollRange) {
		// TODO mWallpaperManager.setWallpaperOffsetSteps(1.0f /
		// (getChildCount() - 1), 0 );
		try {
			mWallpaperManager.setWallpaperOffsets(getWindowToken(),
					getScrollX() / (float) scrollRange, 0);
			Method setWallpaperOffsetSteps = mWallpaperManager.getClass()
					.getMethod("setWallpaperOffsetSteps", float.class,
							float.class);
			setWallpaperOffsetSteps.invoke(mWallpaperManager,
					1.0f / (getChildCount() - 1), 0);
		} catch (Exception e) {

		}
	}

	@Override
	public void computeScroll() {
		if (mScroller.computeScrollOffset()) {
			scrollTo(mScroller.getCurrX(), mScroller.getCurrY());
			updateWallpaperOffset();
			postInvalidate();
		} else if (mNextScreen != INVALID_SCREEN) {
			int lastScreen = mCurrentScreen;
			mCurrentScreen = Math.max(0,
					Math.min(mNextScreen, getChildCount() - 1));

			// set screen and indicator
			Launcher.setScreen(mCurrentScreen);
			if (mLauncher != null)
				mLauncher.indicateScreen(mCurrentScreen);

			// notify widget about screen changed
			View changedView;
			if (lastScreen != mCurrentScreen) {
				changedView = getChildAt(lastScreen); // A screen get out
				if (changedView instanceof WidgetCellLayout)
					((WidgetCellLayout) changedView).onViewportOut();
			}
			changedView = getChildAt(mCurrentScreen); // A screen get in
			if (changedView instanceof WidgetCellLayout)
				((WidgetCellLayout) changedView).onViewportIn();

			mNextScreen = INVALID_SCREEN;
			clearChildrenCache();
		}
	}

	public boolean isOpaque() {
		return false;
	}

	@Override
	protected void dispatchDraw(Canvas canvas) {
		boolean restore = false;

		// If the all apps drawer is open and the drawing region for the
		// workspace
		// is contained within the drawer's bounds, we skip the drawing. This
		// requires
		// the drawer to be fully opaque.
		// if (mLauncher.isDrawerUp()) {
		// final Rect clipBounds = mClipBounds;
		// canvas.getClipBounds(clipBounds);
		// clipBounds.offset(-getScrollX(), -getScrollY());
		// if (mDrawerBounds.contains(clipBounds)) {
		// return;
		// }
		// } else if (mLauncher.isDrawerMoving()) {
		// restore = true;
		// canvas.save(Canvas.CLIP_SAVE_FLAG);
		//
		// final View view = mLauncher.getDrawerHandle();
		// final int top = view.getTop() + view.getHeight();
		//
		// canvas.clipRect(getScrollX(), top, getScrollX()
		// + mDrawerContentWidth, top + mDrawerContentHeight,
		// Region.Op.DIFFERENCE);
		// }

		// ViewGroup.dispatchDraw() supports many features we don't need:
		// clip to padding, layout animation, animation listener, disappearing
		// children, etc. The following implementation attempts to fast-track
		// the drawing dispatch by drawing only what we know needs to be drawn.

		boolean fastDraw = mTouchState != TOUCH_STATE_SCROLLING
				&& mNextScreen == INVALID_SCREEN;
		// If we are not scrolling or flinging, draw only the current screen
		if (fastDraw) {
			drawChild(canvas, getChildAt(mCurrentScreen), getDrawingTime());
		} else {
			final long drawingTime = getDrawingTime();
			// If we are flinging, draw only the current screen and the target
			// screen
			if (mNextScreen >= 0 && mNextScreen < getChildCount()
					&& Math.abs(mCurrentScreen - mNextScreen) == 1) {
				drawChild(canvas, getChildAt(mCurrentScreen), drawingTime);
				drawChild(canvas, getChildAt(mNextScreen), drawingTime);
			} else {
				// If we are scrolling, draw all of our children
				final int count = getChildCount();
				for (int i = 0; i < count; i++) {
					drawChild(canvas, getChildAt(i), drawingTime);
				}
			}
		}

		if (restore) {
			canvas.restore();
		}
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);

		final int width = MeasureSpec.getSize(widthMeasureSpec);
		final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
		if (widthMode != MeasureSpec.EXACTLY) {
			throw new IllegalStateException(
					"Workspace can only be used in EXACTLY mode.");
		}

		final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		if (heightMode != MeasureSpec.EXACTLY) {
			throw new IllegalStateException(
					"Workspace can only be used in EXACTLY mode.");
		}

		// The children are given the same width and height as the workspace
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			getChildAt(i).measure(widthMeasureSpec, heightMeasureSpec);
		}

		if (mFirstLayout) {
			scrollTo(mCurrentScreen * width, 0);
			updateWallpaperOffset(width * (getChildCount() - 1));
			mFirstLayout = false;
		}
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right,
			int bottom) {
		int childLeft = 0;

		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			final View child = getChildAt(i);
			if (child.getVisibility() != View.GONE) {
				final int childWidth = child.getMeasuredWidth();
				child.layout(childLeft, 0, childLeft + childWidth,
						child.getMeasuredHeight());
				childLeft += childWidth;
			}
		}
	}

	@Override
	public boolean requestChildRectangleOnScreen(View child, Rect rectangle,
			boolean immediate) {
		int screen = indexOfChild(child);
		if (screen != mCurrentScreen || !mScroller.isFinished()) {
			if (!mLauncher.isWorkspaceLocked()) {
				snapToScreen(screen);
			}
			return true;
		}
		return false;
	}

	@Override
	protected boolean onRequestFocusInDescendants(int direction,
			Rect previouslyFocusedRect) {
		// if (mLauncher.isDrawerDown()) {
		// final Folder openFolder = getOpenFolder();
		// if (openFolder != null) {
		// return openFolder
		// .requestFocus(direction, previouslyFocusedRect);
		// } else {
		// int focusableScreen;
		// if (mNextScreen != INVALID_SCREEN) {
		// focusableScreen = mNextScreen;
		// } else {
		// focusableScreen = mCurrentScreen;
		// }
		// getChildAt(focusableScreen).requestFocus(direction,
		// previouslyFocusedRect);
		// }
		// }
		return false;
	}

	@Override
	public boolean dispatchUnhandledMove(View focused, int direction) {
		if (direction == View.FOCUS_LEFT) {
			if (getCurrentScreen() > 0) {
				snapToScreen(getCurrentScreen() - 1);
				return true;
			}
		} else if (direction == View.FOCUS_RIGHT) {
			if (getCurrentScreen() < getChildCount() - 1) {
				snapToScreen(getCurrentScreen() + 1);
				return true;
			}
		}
		return super.dispatchUnhandledMove(focused, direction);
	}

	@Override
	public void addFocusables(ArrayList<View> views, int direction,
			int focusableMode) {
		// if (mLauncher.isDrawerDown()) {
		// final Folder openFolder = getOpenFolder();
		// if (openFolder == null) {
		// getChildAt(mCurrentScreen).addFocusables(views, direction);
		// if (direction == View.FOCUS_LEFT) {
		// if (mCurrentScreen > 0) {
		// getChildAt(mCurrentScreen - 1).addFocusables(views,
		// direction);
		// }
		// } else if (direction == View.FOCUS_RIGHT) {
		// if (mCurrentScreen < getChildCount() - 1) {
		// getChildAt(mCurrentScreen + 1).addFocusables(views,
		// direction);
		// }
		// }
		// } else {
		// openFolder.addFocusables(views, direction);
		// }
		// }
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		// if (mLocked || !mLauncher.isDrawerDown()) {
		// return true;
		// }

		/*
		 * This method JUST determines whether we want to intercept the motion.
		 * If we return true, onTouchEvent will be called and we do the actual
		 * scrolling there.
		 */

		/*
		 * Shortcut the most recurring case: the user is in the dragging state
		 * and he is moving his finger. We want to intercept this motion.
		 */
		final int action = ev.getAction();
		if ((action == MotionEvent.ACTION_MOVE)
				&& (mTouchState != TOUCH_STATE_REST)) {
			return true;
		}

		final float x = ev.getX();
		final float y = ev.getY();

		switch (action) {
		case MotionEvent.ACTION_MOVE:
			/*
			 * mIsBeingDragged == false, otherwise the shortcut would have
			 * caught it. Check whether the user has moved far enough from his
			 * original down touch.
			 */

			/*
			 * Locally do absolute value. mLastMotionX is set to the y value of
			 * the down event.
			 */
			final int xDiff = (int) Math.abs(x - mLastMotionX);
			final int yDiff = (int) Math.abs(y - mLastMotionY);

			final int touchSlop = mTouchSlop;
			boolean xMoved = xDiff > touchSlop;
			boolean yMoved = yDiff > touchSlop;

			if (xMoved || yMoved) {

				if (xMoved) {
					// Scroll if the user moved far enough along the X axis
					mTouchState = TOUCH_STATE_SCROLLING;
					enableChildrenCache();
				}
				// Either way, cancel any pending longpress
				if (mAllowLongPress) {
					mAllowLongPress = false;
					// Try canceling the long press. It could also have been
					// scheduled
					// by a distant descendant, so use the mAllowLongPress flag
					// to block
					// everything
					final View currentScreen = getChildAt(mCurrentScreen);
					currentScreen.cancelLongPress();
				}
			}
			break;

		case MotionEvent.ACTION_DOWN:
			// Remember location of down touch
			mLastMotionX = x;
			mLastMotionY = y;
			mAllowLongPress = true;

			/*
			 * If being flinged and user touches the screen, initiate drag;
			 * otherwise don't. mScroller.isFinished should be false when being
			 * flinged.
			 */
			mTouchState = mScroller.isFinished() ? TOUCH_STATE_REST
					: TOUCH_STATE_SCROLLING;
			break;

		case MotionEvent.ACTION_CANCEL:
		case MotionEvent.ACTION_UP:
			// Release the drag
			clearChildrenCache();
			mTouchState = TOUCH_STATE_REST;
			mAllowLongPress = false;
			break;
		}

		/*
		 * The only time we want to intercept motion events is if we are in the
		 * drag mode.
		 */
		return mTouchState != TOUCH_STATE_REST;
	}

	void enableChildrenCache() {
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			final LayoutType layout = (LayoutType) getChildAt(i);
			layout.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_LOW);
			layout.setChildrenDrawnWithCacheEnabled(true);
			layout.setChildrenDrawingCacheEnabled(true);
		}
	}

	void clearChildrenCache() {
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			final LayoutType layout = (LayoutType) getChildAt(i);
			layout.setChildrenDrawnWithCacheEnabled(false);
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		// if (mLocked || !mLauncher.isDrawerDown()) {
		// return true;
		// }

		if (mVelocityTracker == null) {
			mVelocityTracker = VelocityTracker.obtain();
		}
		mVelocityTracker.addMovement(ev);

		final int action = ev.getAction();
		final float x = ev.getX();

		switch (action) {
		case MotionEvent.ACTION_DOWN:
			/*
			 * If being flinged and user touches, stop the fling. isFinished
			 * will be false if being flinged.
			 */
			if (!mScroller.isFinished()) {
				mScroller.abortAnimation();
			}

			// Remember where the motion event started
			mLastMotionX = x;
			break;
		case MotionEvent.ACTION_MOVE:
			if (mTouchState == TOUCH_STATE_SCROLLING) {
				// Scroll to follow the motion event
				final int deltaX = (int) (mLastMotionX - x);
				mLastMotionX = x;

				if (deltaX < 0) {
					if (getScrollX() > 0) {
						scrollBy(Math.max(-getScrollX(), deltaX), 0);
						updateWallpaperOffset();
					}
				} else if (deltaX > 0) {
					final int availableToScroll = getChildAt(
							getChildCount() - 1).getRight()
							- getScrollX() - getWidth();
					if (availableToScroll > 0) {
						scrollBy(Math.min(availableToScroll, deltaX), 0);
						updateWallpaperOffset();
					}
				}

				if (mLauncher != null) {
					mLauncher.showIndicator();
					mLauncher.mScreenHandler
							.removeMessages(Launcher.MSG_SCR_INDICATOR_DECAY);
				}
			}
			break;
		case MotionEvent.ACTION_UP:
			if (mTouchState == TOUCH_STATE_SCROLLING) {
				final VelocityTracker velocityTracker = mVelocityTracker;
				velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
				int velocityX = (int) velocityTracker.getXVelocity();

				if (velocityX > SNAP_VELOCITY && mCurrentScreen > 0) {
					// Fling hard enough to move left
					snapToScreen(mCurrentScreen - 1);
				} else if (velocityX < -SNAP_VELOCITY
						&& mCurrentScreen < getChildCount() - 1) {
					// Fling hard enough to move right
					snapToScreen(mCurrentScreen + 1);
				} else {
					snapToDestination();
				}

				if (mVelocityTracker != null) {
					mVelocityTracker.recycle();
					mVelocityTracker = null;
				}
			}
			mTouchState = TOUCH_STATE_REST;
			break;
		case MotionEvent.ACTION_CANCEL:
			mTouchState = TOUCH_STATE_REST;
		}

		return true;
	}

	private void snapToDestination() {
		final int screenWidth = getWidth();
		final int whichScreen = (getScrollX() + (screenWidth / 2))
				/ screenWidth;

		snapToScreen(whichScreen);
	}

	void snapToScreen(int whichScreen) {
		if (!mScroller.isFinished())
			return;

		clearVacantCache();
		enableChildrenCache();

		whichScreen = Math.max(0, Math.min(whichScreen, getChildCount() - 1));
		boolean changingScreens = whichScreen != mCurrentScreen;

		mNextScreen = whichScreen;

		View focusedChild = getFocusedChild();
		if (focusedChild != null && changingScreens
				&& focusedChild == getChildAt(mCurrentScreen)) {
			focusedChild.clearFocus();
		}

		final int newX = whichScreen * getWidth();
		final int delta = newX - getScrollX();
		mScroller.startScroll(getScrollX(), 0, delta, 0, Math.abs(delta) * 2);
		invalidate();
	}

	void startDrag(LayoutType.CellInfo cellInfo) {
		View child = cellInfo.cell;

		// Make sure the drag was started by a long press as opposed to a long
		// click.
		// Note that Search takes focus when clicked rather than entering touch
		// mode

		if (!child.isInTouchMode() && !(child instanceof Search)) {
			return;
		}

		// --
		if (child instanceof SpeechBubble) {
			return;
		}

		mDragInfo = cellInfo;
		mDragInfo.screen = mCurrentScreen;

		LayoutType current = ((LayoutType) getChildAt(mCurrentScreen));

		current.onDragChild(child);
		mDragger.startDrag(child, this, child.getTag(),
				DragController.DRAG_ACTION_MOVE);

		invalidate();
	}

	@Override
	protected Parcelable onSaveInstanceState() {
		final SavedState state = new SavedState(super.onSaveInstanceState());
		state.currentScreen = mCurrentScreen;
		return state;
	}

	@Override
	protected void onRestoreInstanceState(Parcelable state) {
		SavedState savedState = (SavedState) state;
		super.onRestoreInstanceState(savedState.getSuperState());
		if (savedState.currentScreen != -1) {
			mCurrentScreen = savedState.currentScreen;
			Launcher.setScreen(mCurrentScreen);
		}
	}

	void addApplicationShortcut(ItemInfo info, LayoutType.CellInfo cellInfo,
			boolean insertAtFirst) {
		final LayoutType layout = (LayoutType) getChildAt(cellInfo.screen);
		final int[] result = new int[2];

		onDropExternal(result[0], result[1], info, layout, insertAtFirst);
	}

	public void onDrop(DragSource source, int x, int y, int xOffset,
			int yOffset, Object dragInfo) {

		final LayoutType layoutType = getCurrentDropLayout();

		// 수정모드 애니메이션 시작
		mLauncher.modifyAnimationStart();

		if (source != this) {
			onDropExternal(x - xOffset, y - yOffset, dragInfo, layoutType);
		} else {
			// Move internally
			if (mDragInfo != null) {
				final View cell = mDragInfo.cell;
				int index = mScroller.isFinished() ? mCurrentScreen
						: mNextScreen;
				if (index != mDragInfo.screen) {
					final LayoutType originalLayoutType = (LayoutType) getChildAt(mDragInfo.screen);
					originalLayoutType.removeView(cell);

					// --
					if (cell instanceof MobjectImageView) {
						MLayout mLayout = (MLayout) originalLayoutType;
						mLayout.removeAvatarView((MobjectImageView) cell);
					}

					layoutType.addView(cell);
				}

				final ItemInfo info = (ItemInfo) cell.getTag();
				LayoutType.LayoutParams lp = (LayoutType.LayoutParams) cell
						.getLayoutParams();

				// -- MLayout 관련
				if (layoutType instanceof MLayout) {
					layoutType.onDropChild(cell, x - xOffset, y - yOffset);

				} else {
					mTargetCell = estimateDropCell(x - xOffset, y - yOffset,
							mDragInfo.spanX, mDragInfo.spanY, cell, layoutType,
							mTargetCell);
					layoutType.onDropChild(cell, mTargetCell);
				}

				LauncherModel.moveItemInDatabase(mLauncher, info,
						LauncherSettings.Favorites.CONTAINER_DESKTOP, index,
						lp.cellX, lp.cellY);
			}
		}
	}

	public void onDragEnter(DragSource source, int x, int y, int xOffset,
			int yOffset, Object dragInfo) {
		clearVacantCache();
	}

	public void onDragOver(DragSource source, int x, int y, int xOffset,
			int yOffset, Object dragInfo) {
	}

	public void onDragExit(DragSource source, int x, int y, int xOffset,
			int yOffset, Object dragInfo) {
		clearVacantCache();
	}

	private void onDropExternal(int x, int y, Object dragInfo,
			LayoutType layoutType) {
		onDropExternal(x, y, dragInfo, layoutType, false);
	}

	private void onDropExternal(int x, int y, Object dragInfo,
			LayoutType layoutType, boolean insertAtFirst) {
		// Drag from somewhere else
		ItemInfo info = (ItemInfo) dragInfo;
		
		View view = null;

		switch (info.itemType) {
		case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
		case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
			if (info instanceof ApplicationInfo) {
				Log.e("RRR", "onDropExternal, ApplicationInfo");
				if (info.container == NO_ID) {
					// Came from all apps -- make a copy
					info = new ApplicationInfo((ApplicationInfo) info);
				}
				view = mLauncher.createShortcut(R.layout.application,
						layoutType, (ApplicationInfo) info);
			} else if (info instanceof Mobject) {
				Log.e("RRR", "onDropExternal, Mobject");
				info = new Mobject((Mobject) info);

				view = mLauncher.createShortcut(R.layout.mobject, layoutType,
						(Mobject) info);
			}
			break;
//		case LauncherSettings.Favorites.ITEM_TYPE_USER_FOLDER:
//			view = FolderIcon.fromXml(R.layout.folder_icon, mLauncher,
//					(ViewGroup) getChildAt(mCurrentScreen),
//					((UserFolderInfo) info));
//			break;
		default:
			throw new IllegalStateException("Unknown item type: "
					+ info.itemType);
		}
		layoutType.addView(view, insertAtFirst ? 0 : -1);
		view.setOnLongClickListener(mLongClickListener);

		// -- MLayout 관련
		LayoutType.LayoutParams lp = (LayoutType.LayoutParams) view
				.getLayoutParams();
		if (layoutType instanceof MLayout) {
			layoutType.onDropChild(view, x, y);

		} else {

			mTargetCell = estimateDropCell(x, y, 1, 1, view, layoutType,
					mTargetCell);
			layoutType.onDropChild(view, mTargetCell);
		}

		final LauncherModel model = Launcher.getModel();
		model.addDesktopItem(info);
		LauncherModel.addOrMoveItemInDatabase(mLauncher, info,
				LauncherSettings.Favorites.CONTAINER_DESKTOP, mCurrentScreen,
				lp.cellX, lp.cellY);
	}

	/**
	 * Return the current {@link CellLayout||MLayout}, correctly picking the
	 * destination screen while a scroll is in progress.
	 */
	private LayoutType getCurrentDropLayout() {
		int index = mScroller.isFinished() ? mCurrentScreen : mNextScreen;
		return (LayoutType) getChildAt(index);
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean acceptDrop(DragSource source, int x, int y, int xOffset,
			int yOffset, Object dragInfo) {
		final LayoutType layout = getCurrentDropLayout();

		if (layout instanceof MLayout) {
			return true;
		} else {
			final LayoutType.CellInfo cellInfo = mDragInfo;
			final int spanX = cellInfo == null ? 1 : cellInfo.spanX;
			final int spanY = cellInfo == null ? 1 : cellInfo.spanY;

			if (mVacantCache == null) {
				final View ignoreView = cellInfo == null ? null : cellInfo.cell;
				mVacantCache = layout.findAllVacantCells(null, ignoreView);
			}

			return mVacantCache.findCellForSpan(mTempEstimate, spanX, spanY,
					false);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public Rect estimateDropLocation(int x, int y, int xOffset, int yOffset,
			Rect recycle) {
		final LayoutType layout = getCurrentDropLayout();

		final LayoutType.CellInfo cellInfo = mDragInfo;
		final int spanX = cellInfo == null ? 1 : cellInfo.spanX;
		final int spanY = cellInfo == null ? 1 : cellInfo.spanY;
		final View ignoreView = cellInfo == null ? null : cellInfo.cell;

		final Rect location = recycle != null ? recycle : new Rect();

		// Find drop cell and convert into rectangle
		int[] dropCell;

		if (layout instanceof MLayout) {
			return null;
		} else {
			dropCell = estimateDropCell(x - xOffset, y - yOffset, spanX, spanY,
					ignoreView, layout, mTempCell);
		}

		if (dropCell == null) {
			return null;
		}

		location.left = mTempEstimate[0];
		location.top = mTempEstimate[1];
		location.right = mTempEstimate[0];
		location.bottom = mTempEstimate[1];

		return location;
	}

	/**
	 * Calculate the nearest cell where the given object would be dropped.
	 */
	private int[] estimateDropCell(int pixelX, int pixelY, int spanX,
			int spanY, View ignoreView, LayoutType layout, int[] recycle) {
		// Create vacant cell cache if none exists
		if (mVacantCache == null) {
			mVacantCache = layout.findAllVacantCells(null, ignoreView);
		}

		// Find the best target drop location
		return layout.findNearestVacantArea(pixelX, pixelY, spanX, spanY,
				mVacantCache, recycle);
	}

	void setLauncher(Launcher launcher) {
		mLauncher = launcher;
		registerProvider();
	}

	public void setDragger(DragController dragger) {
		mDragger = dragger;
	}

	public void onDropCompleted(View target, boolean success) {
		// This is a bit expensive but safe
		clearVacantCache();

		if (success) {
			if (target != this && mDragInfo != null) {
				final LayoutType layoutType = (LayoutType) getChildAt(mDragInfo.screen);
				layoutType.removeView(mDragInfo.cell);
				final Object tag = mDragInfo.cell.getTag();
				Launcher.getModel().removeDesktopItem((ItemInfo) tag);
			}
		} else {
			if (mDragInfo != null) {
				final LayoutType layoutType = (LayoutType) getChildAt(mDragInfo.screen);
				layoutType.onDropAborted(mDragInfo.cell);
			}
		}

		mDragInfo = null;
	}

	public void scrollLeft() {
		clearVacantCache();
		if (mNextScreen == INVALID_SCREEN && mCurrentScreen > 0
				&& mScroller.isFinished()) {
			snapToScreen(mCurrentScreen - 1);
		}
	}

	public void scrollRight() {
		clearVacantCache();
		if (mNextScreen == INVALID_SCREEN
				&& mCurrentScreen < getChildCount() - 1
				&& mScroller.isFinished()) {
			snapToScreen(mCurrentScreen + 1);
		}
	}

	public int getScreenForView(View v) {
		int result = -1;
		if (v != null) {
			ViewParent vp = v.getParent();
			int count = getChildCount();
			for (int i = 0; i < count; i++) {
				if (vp == getChildAt(i)) {
					return i;
				}
			}
		}
		return result;
	}

	/**
	 * Find a search widget on the given screen
	 */
	private Search findSearchWidget(LayoutType screen) {
		final int count = screen.getChildCount();
		for (int i = 0; i < count; i++) {
			View v = screen.getChildAt(i);
			if (v instanceof Search) {
				return (Search) v;
			}
		}
		return null;
	}

	/**
	 * Gets the first search widget on the current screen, if there is one.
	 * Returns <code>null</code> otherwise.
	 */
	public Search findSearchWidgetOnCurrentScreen() {
		LayoutType currentScreen = (LayoutType) getChildAt(mCurrentScreen);
		return findSearchWidget(currentScreen);
	}

	public Folder getFolderForTag(Object tag) {
		int screenCount = getChildCount();
		for (int screen = 0; screen < screenCount; screen++) {
			LayoutType currentScreen = ((LayoutType) getChildAt(screen));
			int count = currentScreen.getChildCount();
			for (int i = 0; i < count; i++) {
				View child = currentScreen.getChildAt(i);
				LayoutType.LayoutParams lp = (LayoutType.LayoutParams) child
						.getLayoutParams();
				if (lp.cellHSpan == 4 && lp.cellVSpan == 4
						&& child instanceof Folder) {
					Folder f = (Folder) child;
					if (f.getInfo() == tag) {
						return f;
					}
				}
			}
		}
		return null;
	}

	public View getViewForTag(Object tag) {
		int screenCount = getChildCount();
		for (int screen = 0; screen < screenCount; screen++) {
			LayoutType currentScreen = ((LayoutType) getChildAt(screen));
			int count = currentScreen.getChildCount();
			for (int i = 0; i < count; i++) {
				View child = currentScreen.getChildAt(i);
				if (child.getTag() == tag) {
					return child;
				}
			}
		}
		return null;
	}

	/**
	 * Unlocks the SlidingDrawer so that touch events are processed.
	 * 
	 * @see #lock()
	 */
	public void unlock() {
		mLocked = false;
	}

	/**
	 * Locks the SlidingDrawer so that touch events are ignores.
	 * 
	 * @see #unlock()
	 */
	public void lock() {
		mLocked = true;
	}

	/**
	 * @return True is long presses are still allowed for the current touch
	 */
	public boolean allowLongPress() {
		return mAllowLongPress;
	}

	/**
	 * Set true to allow long-press events to be triggered, usually checked by
	 * {@link Launcher} to accept or block dpad-initiated long-presses.
	 */
	public void setAllowLongPress(boolean allowLongPress) {
		mAllowLongPress = allowLongPress;
	}

	void removeShortcutsForPackage(String packageName) {
		final ArrayList<View> childrenToRemove = new ArrayList<View>();
		final LauncherModel model = Launcher.getModel();
		final int count = getChildCount();

		for (int i = 0; i < count; i++) {
			final LayoutType layout = (LayoutType) getChildAt(i);
			int childCount = layout.getChildCount();

			childrenToRemove.clear();

			for (int j = 0; j < childCount; j++) {
				final View view = layout.getChildAt(j);
				Object tag = view.getTag();

				if (tag instanceof ApplicationInfo) {
					final ApplicationInfo info = (ApplicationInfo) tag;
					// We need to check for ACTION_MAIN otherwise getComponent()
					// might
					// return null for some shortcuts (for instance, for
					// shortcuts to
					// web pages.)
					final Intent intent = info.intent;
					final ComponentName name = intent.getComponent();

					if (Intent.ACTION_MAIN.equals(intent.getAction())
							&& name != null
							&& packageName.equals(name.getPackageName())) {
						model.removeDesktopItem(info);
						LauncherModel.deleteItemFromDatabase(mLauncher, info);
						childrenToRemove.add(view);
					}
				} else if (tag instanceof UserFolderInfo) {
					final UserFolderInfo info = (UserFolderInfo) tag;
					final ArrayList<ItemInfo> contents = info.contents;
					final ArrayList<ItemInfo> toRemove = new ArrayList<ItemInfo>(
							1);
					final int contentsCount = contents.size();
					boolean removedFromFolder = false;

					for (int k = 0; k < contentsCount; k++) {
						final ItemInfo appInfo = contents.get(k);
						final Intent intent = appInfo.intent;
						final ComponentName name = intent.getComponent();

						if (Intent.ACTION_MAIN.equals(intent.getAction())
								&& name != null
								&& packageName.equals(name.getPackageName())) {
							toRemove.add(appInfo);
							LauncherModel.deleteItemFromDatabase(mLauncher,
									appInfo);
							removedFromFolder = true;
						}
					}

					contents.removeAll(toRemove);
					if (removedFromFolder) {
						final Folder folder = getOpenFolder();
						if (folder != null)
							folder.notifyDataSetChanged();
					}
				}
			}

			childCount = childrenToRemove.size();
			for (int j = 0; j < childCount; j++) {
				layout.removeViewInLayout(childrenToRemove.get(j));
			}

			if (childCount > 0) {
				layout.requestLayout();
				layout.invalidate();
			}
		}
	}

	void updateShortcutsForPackage(String packageName) {
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			final LayoutType layout = (LayoutType) getChildAt(i);
			int childCount = layout.getChildCount();
			for (int j = 0; j < childCount; j++) {
				final View view = layout.getChildAt(j);
				Object tag = view.getTag();
				if (tag instanceof ApplicationInfo) {
					ApplicationInfo info = (ApplicationInfo) tag;
					// We need to check for ACTION_MAIN otherwise getComponent()
					// might
					// return null for some shortcuts (for instance, for
					// shortcuts to
					// web pages.)
					final Intent intent = info.intent;
					final ComponentName name = intent.getComponent();
					if (info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
							&& Intent.ACTION_MAIN.equals(intent.getAction())
							&& name != null
							&& packageName.equals(name.getPackageName())) {

						final Drawable icon = Launcher.getModel()
								.getApplicationInfoIcon(
										mLauncher.getPackageManager(), info);
						if (icon != null && icon != info.icon) {
							info.icon.setCallback(null);
							info.icon = Utilities.createIconThumbnail(icon,
									getContext());
							info.filtered = true;
							((TextView) view)
									.setCompoundDrawablesWithIntrinsicBounds(
											null, info.icon, null, null);
						}
					}
				}
			}
		}
	}

	void moveToDefaultScreen() {
		snapToScreen(mDefaultScreen);
		getChildAt(mDefaultScreen).requestFocus();
	}

	public static class SavedState extends BaseSavedState {
		int currentScreen = -1;

		SavedState(Parcelable superState) {
			super(superState);
		}

		private SavedState(Parcel in) {
			super(in);
			currentScreen = in.readInt();
		}

		@Override
		public void writeToParcel(Parcel out, int flags) {
			super.writeToParcel(out, flags);
			out.writeInt(currentScreen);
		}

		public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
			public SavedState createFromParcel(Parcel in) {
				return new SavedState(in);
			}

			public SavedState[] newArray(int size) {
				return new SavedState[size];
			}
		};
	}

	@Override
	public Activity getLauncherActivity() {
		return mLauncher;
	}

	public boolean isWidgetAtLocationScrollable(int x, int y) {
		// will return true if widget at this position is scrollable.
		// Get current screen from the whole desktop
		LayoutType currentScreen = (LayoutType) getChildAt(mCurrentScreen);
		int[] cell_xy = new int[2];
		// Get the cell where the user started the touch event
		currentScreen.pointToCellExact(x, y, cell_xy);
		int count = currentScreen.getChildCount();

		Log.d("Workspace", "pointToCellExact : x = " + x + " / y = " + y
				+ " / cellX = " + cell_xy[0] + " / cellY = " + cell_xy[1]);

		// Iterate to find which widget is located at that cell
		// Find widget backwards from a cell does not work with
		// (View)currentScreen.getChildAt(cell_xy[0]*currentScreen.getCountX etc
		// etc); As the widget is positioned at the very first cell of the
		// widgetspace
		for (int i = 0; i < count; i++) {
			View child = (View) currentScreen.getChildAt(i);
			if (child != null) {
				// Get Layount graphical info about this widget
				LayoutType.LayoutParams lp = (LayoutType.LayoutParams) child
						.getLayoutParams();
				// Calculate Cell Margins
				int left_cellmargin = lp.cellX;
				int rigth_cellmargin = lp.cellX + lp.cellHSpan;
				int top_cellmargin = lp.cellY;
				int botton_cellmargin = lp.cellY + lp.cellVSpan;
				// See if the cell where we touched is inside the Layout of the
				// widget beeing analized
				if (cell_xy[0] >= left_cellmargin
						&& cell_xy[0] < rigth_cellmargin
						&& cell_xy[1] >= top_cellmargin
						&& cell_xy[1] < botton_cellmargin) {
					try {
						// Get Widget ID
						int id = ((AppWidgetHostView) child).getAppWidgetId();
						// Ask to WidgetSpace if the Widget identified itself
						// when created as 'Scrollable'
						return isWidgetScrollable(id);
					} catch (Exception e) {
					}
				}
			}
		}
		return false;
	}

	public void unbindWidgetScrollableViews() {
		unbindWidgetScrollable();
	}

	public void initMScreens() {
		for (int i = 0; i < this.getChildCount(); i++) {
			LayoutType layout = (LayoutType) this.getChildAt(i);
			if (layout instanceof MLayout) {
				MLayout mLayout = (MLayout) layout;
				mLayout.initMLayout(mLauncher, i);
			}
		}
	}
}
