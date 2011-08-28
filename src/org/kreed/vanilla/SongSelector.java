/*
 * Copyright (C) 2010 Christopher Eby <kreed@kreed.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.kreed.vanilla;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.PaintDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.AdapterView;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

public class SongSelector extends PlaybackActivity implements AdapterView.OnItemClickListener, TextWatcher, TabHost.OnTabChangeListener, Filter.FilterListener {
	/**
	 * The number of tabs in the song selector.
	 */
	private static final int TAB_COUNT = 4;

	private TabHost mTabHost;

	private View mSearchBox;
	private boolean mSearchBoxVisible;
	private TextView mTextFilter;
	private View mClearButton;

	private View mControls;
	TextView mStatusText;
	ImageView mCover;

	private ViewGroup mLimiterViews;

	private String mDefaultAction;
	private boolean mDefaultIsLastAction;

	private long mLastActedId;

	private MediaAdapter[] mAdapters;
	private MediaAdapter mArtistAdapter;
	private MediaAdapter mAlbumAdapter;
	private MediaAdapter mSongAdapter;
	private MediaAdapter mPlaylistAdapter;
	private MediaAdapter mCurrentAdapter;

	@Override
	public void onCreate(Bundle state)
	{
		super.onCreate(state);

		setContentView(R.layout.song_selector);

		mTabHost = (TabHost)findViewById(android.R.id.tabhost);
		mTabHost.setup();

		Resources res = getResources();
		mTabHost.addTab(mTabHost.newTabSpec("tab_artists").setIndicator(res.getText(R.string.artists), res.getDrawable(R.drawable.tab_artists)).setContent(R.id.artist_list));
		mTabHost.addTab(mTabHost.newTabSpec("tab_albums").setIndicator(res.getText(R.string.albums), res.getDrawable(R.drawable.tab_albums)).setContent(R.id.album_list));
		mTabHost.addTab(mTabHost.newTabSpec("tab_songs").setIndicator(res.getText(R.string.songs), res.getDrawable(R.drawable.tab_songs)).setContent(R.id.song_list));
		mTabHost.addTab(mTabHost.newTabSpec("tab_playlists").setIndicator(res.getText(R.string.playlists), res.getDrawable(R.drawable.tab_playlists)).setContent(R.id.playlist_list));

		mSearchBox = findViewById(R.id.search_box);

		mTextFilter = (TextView)findViewById(R.id.filter_text);
		mTextFilter.addTextChangedListener(this);

		mClearButton = findViewById(R.id.clear_button);
		mClearButton.setOnClickListener(this);

		mLimiterViews = (ViewGroup)findViewById(R.id.limiter_layout);

		mArtistAdapter = setupView(R.id.artist_list, new MediaAdapter(this, MediaUtils.TYPE_ARTIST, true, false, null));
		mAlbumAdapter = setupView(R.id.album_list, new MediaAdapter(this, MediaUtils.TYPE_ALBUM, true, false, state == null ? null : (MediaAdapter.Limiter)state.getSerializable("limiter_albums")));
		mSongAdapter = setupView(R.id.song_list, new SongMediaAdapter(this, false, false, state == null ? null : (MediaAdapter.Limiter)state.getSerializable("limiter_songs")));
		mPlaylistAdapter = setupView(R.id.playlist_list, new MediaAdapter(this, MediaUtils.TYPE_PLAYLIST, false, true, null));
		mAdapters = new MediaAdapter[] { mArtistAdapter, mAlbumAdapter, mSongAdapter, mPlaylistAdapter };

		mTabHost.setOnTabChangedListener(this);

		if (state != null) {
			if (state.getBoolean("search_box_visible"))
				setSearchBoxVisible(true);
			int currentTab = state.getInt("current_tab", -1);
			if (currentTab != -1)
				mTabHost.setCurrentTab(currentTab);
			mTextFilter.setText(state.getString("filter"));
		}
	}

	@Override
	public void onStart()
	{
		super.onStart();

		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
		boolean showControls = settings.getBoolean("controls_in_selector", false);
		if (showControls && mControls == null) {
			mControls = findViewById(R.id.controls);
			if (mControls == null)
				mControls = ((ViewStub)findViewById(R.id.controls_stub)).inflate();
			mStatusText = (TextView)mControls.findViewById(R.id.status_text);
			mCover = (ImageView)mControls.findViewById(R.id.cover);
			ControlButton previous = (ControlButton)mControls.findViewById(R.id.previous);
			mPlayPauseButton = (ControlButton)mControls.findViewById(R.id.play_pause);
			ControlButton next = (ControlButton)mControls.findViewById(R.id.next);

			mCover.setOnClickListener(this);
			previous.setOnClickListener(this);
			mPlayPauseButton.setOnClickListener(this);
			next.setOnClickListener(this);

			if (mSearchBoxVisible)
				mControls.setVisibility(View.GONE);

			if (ContextApplication.hasService()) {
				PlaybackService service = ContextApplication.getService();
				// Force the state to be updated, even if PlaybackActivity has
				// already loaded it. This is needed to set the proper image
				// for the play/pause button.
				mState = 0;
				setState(service.getState());
				onSongChange(service.getSong(0));
			}
		} else if (!showControls && mControls != null) {
			mControls.setVisibility(View.GONE);
			mControls = null;
		}

		int action = Integer.parseInt(settings.getString("default_action_int", "0"));
		mDefaultAction = action == 1 ? PlaybackService.ACTION_ENQUEUE_ITEMS : PlaybackService.ACTION_PLAY_ITEMS;
		mDefaultIsLastAction = action == 2;
		mLastActedId = 0;
	}

	@Override
	protected void onSaveInstanceState(Bundle out)
	{
		out.putBoolean("search_box_visible", mSearchBoxVisible);
		out.putInt("current_tab", mTabHost.getCurrentTab());
		out.putString("filter", mTextFilter.getText().toString());
		out.putSerializable("limiter_albums", mAlbumAdapter.getLimiter());
		out.putSerializable("limiter_songs", mSongAdapter.getLimiter());
	}

	@Override
	protected void onServiceReady()
	{
		super.onServiceReady();

		if (mStatusText != null)
			onSongChange(ContextApplication.getService().getSong(0));
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event)
	{
		switch (keyCode) {
		case KeyEvent.KEYCODE_BACK:
			if (mSearchBoxVisible) {
				mTextFilter.setText("");
				setSearchBoxVisible(false);
			} else {
				sendFinishEnqueueing();
				finish();
			}
			break;
		case KeyEvent.KEYCODE_SEARCH:
			setSearchBoxVisible(!mSearchBoxVisible);
			break;
		default:
			return false;
		}

		return true;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event)
	{
		if (super.onKeyDown(keyCode, event))
			return true;

		if (mTextFilter.onKeyDown(keyCode, event)) {
			if (!mSearchBoxVisible)
				setSearchBoxVisible(true);
			else
				mTextFilter.requestFocus();
			return true;
		}

		return false;
	}

	private void sendSongIntent(MediaAdapter.MediaView view, String action)
	{
		int res = PlaybackService.ACTION_PLAY_ITEMS.equals(action) ? R.string.playing : R.string.enqueued;
		String text = getResources().getString(res, view.getTitle());
		Toast.makeText(this, text, Toast.LENGTH_SHORT).show();

		long id = view.getMediaId();

		Intent intent = new Intent(this, PlaybackService.class);
		intent.setAction(action);
		intent.putExtra("type", view.getMediaType());
		intent.putExtra("id", id);
		startService(intent);

		mLastActedId = id;
	}

	/**
	 * Tell the PlaybackService that we are finished enqueueing songs.
	 */
	private void sendFinishEnqueueing()
	{
		Intent intent = new Intent(this, PlaybackService.class);
		intent.setAction(PlaybackService.ACTION_FINISH_ENQUEUEING);
		startService(intent);
	}

	private void expand(MediaAdapter.MediaView view)
	{
		mTabHost.setCurrentTab(setLimiter(view.getLimiter()));
	}

	/**
	 * Update the adapters with the given limiter.
	 *
	 * @return The tab to "expand" to
	 */
	private int setLimiter(MediaAdapter.Limiter limiter)
	{
		if (limiter == null) {
			mAlbumAdapter.setLimiter(null, true);
			mSongAdapter.setLimiter(null, true);
			return -1;
		}

		switch (limiter.type) {
		case MediaUtils.TYPE_ALBUM:
			mSongAdapter.setLimiter(limiter, false);
			return 2;
		case MediaUtils.TYPE_ARTIST:
			mAlbumAdapter.setLimiter(limiter, false);
			mSongAdapter.setLimiter(limiter, true);
			return 1;
		default:
			throw new IllegalArgumentException("Unsupported limiter type: " + limiter.type);
		}
	}

	public void onItemClick(AdapterView<?> list, View view, int pos, long id)
	{
		MediaAdapter.MediaView mediaView = (MediaAdapter.MediaView)view;
		if (mediaView.isExpanderPressed())
			expand(mediaView);
		else if (id == mLastActedId)
			startActivity(new Intent(this, FullPlaybackActivity.class));
		else
			sendSongIntent(mediaView, mDefaultAction);
	}

	public void afterTextChanged(Editable editable)
	{
	}

	public void beforeTextChanged(CharSequence s, int start, int count, int after)
	{
	}

	public void onTextChanged(CharSequence text, int start, int before, int count)
	{
		MediaAdapter adapter = mCurrentAdapter;
		if (adapter != null)
			adapter.filter(text, this);
	}

	private void updateLimiterViews()
	{
		if (mLimiterViews == null)
			return;

		mLimiterViews.removeAllViews();

		MediaAdapter adapter = mCurrentAdapter;
		if (adapter == null)
			return;

		MediaAdapter.Limiter limiterData = adapter.getLimiter();
		if (limiterData == null)
			return;
		String[] limiter = limiterData.names;

		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		params.leftMargin = 5;
		for (int i = 0; i != limiter.length; ++i) {
			PaintDrawable background = new PaintDrawable(Color.GRAY);
			background.setCornerRadius(5);

			TextView view = new TextView(this);
			view.setSingleLine();
			view.setEllipsize(TextUtils.TruncateAt.MARQUEE);
			view.setText(limiter[i] + " | X");
			view.setTextColor(Color.WHITE);
			view.setBackgroundDrawable(background);
			view.setLayoutParams(params);
			view.setPadding(5, 2, 5, 2);
			view.setTag(i);
			view.setOnClickListener(this);
			mLimiterViews.addView(view);
		}
	}

	public void onTabChanged(String tabId)
	{
		mCurrentAdapter = mAdapters[mTabHost.getCurrentTab()];
		updateLimiterViews();
	}

	@Override
	public void onClick(View view)
	{
		if (view == mClearButton) {
			if (mTextFilter.getText().length() == 0)
				setSearchBoxVisible(false);
			else
				mTextFilter.setText("");
		} else if (view == mCover) {
			startActivity(new Intent(this, FullPlaybackActivity.class));
		} else if (view.getTag() != null) {
			// a limiter view was clicked

			int i = (Integer)view.getTag();
			if (i == 1) {
				// generate the artist limiter (we need to query the artist id)
				MediaAdapter.Limiter limiter = mSongAdapter.getLimiter();
				assert(limiter.type == MediaUtils.TYPE_ALBUM);

				ContentResolver resolver = getContentResolver();
				Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
				String[] projection = new String[] { MediaStore.Audio.Media.ARTIST_ID };
				Cursor cursor = resolver.query(uri, projection, limiter.selection, null, null);
				if (cursor != null) {
					if (cursor.moveToNext()) {
						limiter = new MediaAdapter.Limiter(cursor.getLong(0), MediaUtils.TYPE_ARTIST, MediaStore.Audio.Media.ARTIST_ID, new String[] { limiter.names[0] });
						setLimiter(limiter);
						updateLimiterViews();
						cursor.close();
						return;
					}
					cursor.close();
				}
			}

			setLimiter(null);
			updateLimiterViews();
		} else {
			super.onClick(view);
		}
	}

	private static final int MENU_PLAY = 0;
	private static final int MENU_ENQUEUE = 1;
	private static final int MENU_EXPAND = 2;
	private static final int MENU_ADD_TO_PLAYLIST = 3;
	private static final int MENU_NEW_PLAYLIST = 4;
	private static final int MENU_DELETE = 5;
	private static final int MENU_EDIT = 6;
	private static final int MENU_RENAME_PLAYLIST = 7;

	@Override
	public void onCreateContextMenu(ContextMenu menu, View listView, ContextMenu.ContextMenuInfo absInfo)
	{
		MediaAdapter.MediaView view = (MediaAdapter.MediaView)((AdapterView.AdapterContextMenuInfo)absInfo).targetView;
		int type = view.getMediaType();
		int id = (int)view.getMediaId();

		menu.setHeaderTitle(view.getTitle());
		menu.add(0, MENU_PLAY, 0, R.string.play);
		menu.add(0, MENU_ENQUEUE, 0, R.string.enqueue);
		if (view.getMediaType() == MediaUtils.TYPE_PLAYLIST) {
			menu.add(0, MENU_RENAME_PLAYLIST, 0, R.string.rename);
			menu.add(0, MENU_EDIT, 0, R.string.edit);
		}
		SubMenu playlistMenu = menu.addSubMenu(0, MENU_ADD_TO_PLAYLIST, 0, R.string.add_to_playlist);
		if (view.hasExpanders())
			menu.add(0, MENU_EXPAND, 0, R.string.expand);
		menu.add(0, MENU_DELETE, 0, R.string.delete);

		playlistMenu.add(type, MENU_NEW_PLAYLIST, id, R.string.new_playlist);
		Playlist[] playlists = Playlist.getPlaylists();
		if (playlists != null) {
			for (int i = 0; i != playlists.length; ++i)
				playlistMenu.add(type, (int)playlists[i].id + 100, id, playlists[i].name);
		}
	}

	/**
	 * Add a set of songs to a playlists. Sets can be all songs from an artist,
	 * album, playlist, or a single song. Displays a Toast notifying of
	 * success.
	 *
	 * @param playlistId The MediaStore.Audio.Playlists id of the playlist to
	 * be modified.
	 * @param type The type of media the mediaId represents; one of the
	 * Song.TYPE_* constants.
	 * @param mediaId The MediaStore id of the element to be added.
	 * @param title The title of the playlist being added to (used for the
	 * Toast).
	 */
	private void addToPlaylist(long playlistId, int type, long mediaId, CharSequence title)
	{
		long[] ids = MediaUtils.getAllSongIdsWith(type, mediaId);
		Playlist.addToPlaylist(playlistId, ids);

		String message = getResources().getQuantityString(R.plurals.added_to_playlist, ids.length, ids.length, title);
		Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
	}

	/**
	 * Delete the media with the specified type and id and show a Toast
	 * informing the user of this.
	 *
	 * @param type The type of media; one of the MediaUtils.TYPE_* constants.
	 * @param id The MediaStore id of the media.
	 * @param title The title of the playlist, to be displayed in the Toast.
	 * Only used when deleting a playlist.
	 */
	private void delete(int type, long id, String title)
	{
		if (type == MediaUtils.TYPE_PLAYLIST) {
			Playlist.deletePlaylist(id);
			String message = getResources().getString(R.string.playlist_deleted, title);
			Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
		} else {
			int count = MediaUtils.deleteMedia(type, id);
			String message = getResources().getQuantityString(R.plurals.deleted, count, count);
			Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
		}
	}

	@Override
	public boolean onContextItemSelected(MenuItem item)
	{
		String action = PlaybackService.ACTION_PLAY_ITEMS;
		int id = item.getItemId();
		int type = item.getGroupId();
		int mediaId = item.getOrder();

		switch (id) {
		case MENU_EXPAND:
			expand((MediaAdapter.MediaView)((AdapterView.AdapterContextMenuInfo)item.getMenuInfo()).targetView);
			break;
		case MENU_ENQUEUE:
			action = PlaybackService.ACTION_ENQUEUE_ITEMS;
			// fall through
		case MENU_PLAY:
			if (mDefaultIsLastAction)
				mDefaultAction = action;
			sendSongIntent((MediaAdapter.MediaView)((AdapterView.AdapterContextMenuInfo)item.getMenuInfo()).targetView, action);
			break;
		case MENU_NEW_PLAYLIST: {
			NewPlaylistDialog dialog = new NewPlaylistDialog(this, null, R.string.create);
			Message message = mHandler.obtainMessage(MSG_NEW_PLAYLIST, type, mediaId);
			message.obj = dialog;
			dialog.setDismissMessage(message);
			dialog.show();
			break;
		}
		case MENU_RENAME_PLAYLIST: {
			MediaAdapter.MediaView view = (MediaAdapter.MediaView)((AdapterView.AdapterContextMenuInfo)item.getMenuInfo()).targetView;
			NewPlaylistDialog dialog = new NewPlaylistDialog(this, view.getTitle(), R.string.rename);
			Message message = mHandler.obtainMessage(MSG_RENAME_PLAYLIST, view.getMediaType(), (int)view.getMediaId());
			message.obj = dialog;
			dialog.setDismissMessage(message);
			dialog.show();
			break;
		}
		case MENU_DELETE: {
			MediaAdapter.MediaView view = (MediaAdapter.MediaView)((AdapterView.AdapterContextMenuInfo)item.getMenuInfo()).targetView;
			type = view.getMediaType();
			if (type != MediaUtils.TYPE_PLAYLIST)
				Toast.makeText(this, R.string.deleting, Toast.LENGTH_SHORT).show();
			Message message = mHandler.obtainMessage(MSG_DELETE, type, (int)view.getMediaId());
			message.obj = view.getTitle();
			mHandler.sendMessage(message);
			break;
		}
		case MENU_EDIT:
			MediaAdapter.MediaView view = (MediaAdapter.MediaView)((AdapterView.AdapterContextMenuInfo)item.getMenuInfo()).targetView;
			Intent intent = new Intent(Intent.ACTION_EDIT);
			intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
			intent.putExtra("playlist", String.valueOf(view.getMediaId()));
			startActivity(intent);
			break;
		default:
			if (id > 100)
				addToPlaylist(id - 100, type, mediaId, item.getTitle());
			return false;
		}

		return true;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		menu.add(0, MENU_PLAYBACK, 0, R.string.playback_view).setIcon(R.drawable.ic_menu_gallery);
		menu.add(0, MENU_SEARCH, 0, R.string.search).setIcon(R.drawable.ic_menu_search);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId()) {
		case MENU_SEARCH:
			setSearchBoxVisible(!mSearchBoxVisible);
			return true;
		case MENU_PLAYBACK:
			sendFinishEnqueueing();
			startActivity(new Intent(this, FullPlaybackActivity.class));
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	public void onFilterComplete(int count)
	{
		CharSequence text = mTextFilter.getText();
		for (int i = TAB_COUNT; --i != -1; )
			mAdapters[i].filter(text, null);
	}

	/**
	 * Hook up a ListView to this Activity and the supplied adapter
	 *
	 * @param id The id of the ListView
	 * @param adapter The adapter to be used
	 */
	private MediaAdapter setupView(int id, MediaAdapter adapter)
	{
		ListView view = (ListView)findViewById(id);
		view.setOnItemClickListener(this);
		view.setOnCreateContextMenuListener(this);
		view.setAdapter(adapter);
		return adapter;
	}

	/**
	 * Call addToPlaylist with the parameters from the given message. The
	 * message must contain the type and id of the media to be added in
	 * arg1 and arg2, respectively. The obj field must be a NewPlaylistDialog
	 * that the name will be taken from.
	 */
	private static final int MSG_NEW_PLAYLIST = 11;
	/**
	 * Delete the songs in the set of media with the specified type and id,
	 * given as arg1 and arg2, respectively. If type is a playlist, the
	 * playlist itself will be deleted, not the songs it contains. The obj
	 * field should contain the playlist name (as a String) if type is a
	 * playlist.
	 */
	private static final int MSG_DELETE = 12;
	/**
	 * Rename the playlist with the parameters from the given message. The
	 * message must contain the type and id of the media to be added in
	 * arg1 and arg2, respectively. The obj field must be a NewPlaylistDialog
	 * that the name will be taken from.
	 */
	private static final int MSG_RENAME_PLAYLIST = 13;

	@Override
	public boolean handleMessage(Message message)
	{
		switch (message.what) {
		case MSG_NEW_PLAYLIST: {
			NewPlaylistDialog dialog = (NewPlaylistDialog)message.obj;
			if (dialog.isAccepted()) {
				String name = dialog.getText();
				long playlistId = Playlist.createPlaylist(name);
				addToPlaylist(playlistId, message.arg1, message.arg2, name);
			}
			break;
		}
		case MSG_DELETE:
			delete(message.arg1, message.arg2, (String)message.obj);
			break;
		case MSG_RENAME_PLAYLIST: {
			NewPlaylistDialog dialog = (NewPlaylistDialog)message.obj;
			if (dialog.isAccepted())
				Playlist.renamePlaylist(message.arg2, dialog.getText());
		}
		default:
			return super.handleMessage(message);
		}

		return true;
	}

	@Override
	public void onMediaChange()
	{
		runOnUiThread(new Runnable() {
			public void run()
			{
				for (int i = 0; i != TAB_COUNT; ++i)
					mAdapters[i].requery();
			}
		});
	}

	private void setSearchBoxVisible(boolean visible)
	{
		mSearchBoxVisible = visible;
		mSearchBox.setVisibility(visible ? View.VISIBLE : View.GONE);
		if (mControls != null)
			mControls.setVisibility(visible ? View.GONE : View.VISIBLE);
		if (visible)
			mSearchBox.requestFocus();
	}

	private static int mCoverSize = -1;

	/**
	 * Call to update the status text for a newly-playing song. Should only be
	 * called after the controls have been initialized.
	 */
	private void onSongChange(final Song song)
	{
		Resources res = getResources();
		CharSequence text;
		if (song == null) {
			text = res.getText(R.string.none);
		} else {
			String title = song.title == null ? res.getString(R.string.unknown) : song.title;
			String artist = song.artist == null ? res.getString(R.string.unknown) : song.artist;
			text = res.getString(R.string.title_by_artist, title, artist);
		}

		if (mCoverSize == -1) {
			DisplayMetrics metrics = ContextApplication.getContext().getResources().getDisplayMetrics();
			mCoverSize = (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 64, metrics);
		}

		final CharSequence result = text;
		final Bitmap cover = CoverBitmap.createScaledBitmap(song, mCoverSize);
		runOnUiThread(new Runnable() {
			public void run()
			{
				mStatusText.setText(result);
				mCover.setImageBitmap(cover);
				mCover.setVisibility(cover == null ? View.GONE : View.VISIBLE);
			}
		});
	}

	@Override
	public void receive(Intent intent)
	{
		super.receive(intent);

		if (mControls != null && PlaybackService.EVENT_CHANGED.equals(intent.getAction()))
			onSongChange((Song)intent.getParcelableExtra("song"));
	}
}
