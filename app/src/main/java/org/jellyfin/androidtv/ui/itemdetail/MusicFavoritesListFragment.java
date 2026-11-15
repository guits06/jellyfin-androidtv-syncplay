package org.jellyfin.androidtv.ui.itemdetail;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.jellyfin.sdk.model.serializer.UUIDSerializerKt;

import java.util.UUID;

public class MusicFavoritesListFragment extends Fragment implements View.OnKeyListener {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        String rawParentId = args != null ? args.getString("ParentId") : null;
        UUID parentId = rawParentId != null ? UUIDSerializerKt.toUUIDOrNull(rawParentId) : null;
        return AlbumDetailScreenKt.createAlbumDetailComposeView(
                requireContext(),
                null,
                parentId,
                true
        );
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return false;
    }
}
