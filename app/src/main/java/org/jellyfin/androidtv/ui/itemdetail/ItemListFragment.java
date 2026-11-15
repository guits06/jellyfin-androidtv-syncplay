package org.jellyfin.androidtv.ui.itemdetail;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.jellyfin.androidtv.util.Utils;

import java.util.UUID;

public class ItemListFragment extends Fragment implements View.OnKeyListener {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        UUID itemId = args != null ? Utils.uuidOrNull(args.getString("ItemId")) : null;
        return AlbumDetailScreenKt.createAlbumDetailComposeView(
                requireContext(),
                itemId,
                null,
                false
        );
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return false;
    }
}
