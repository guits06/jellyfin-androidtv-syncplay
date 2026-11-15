package org.jellyfin.androidtv.ui.browsing;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.jellyfin.androidtv.constant.Extras;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.ItemSortBy;
import org.jellyfin.sdk.model.api.SortOrder;

import kotlinx.serialization.json.Json;

public class BrowseGridFragment extends Fragment implements View.OnKeyListener {
    private BaseItemDto mFolder;
    private String mIncludeType;

    public static class SortOption {
        public String name;
        public ItemSortBy value;
        public SortOrder order;

        public SortOption(String name, ItemSortBy value, SortOrder order) {
            this.name = name;
            this.value = value;
            this.order = order;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        String folderJson = args != null ? args.getString(Extras.Folder) : null;
        if (folderJson != null) {
            mFolder = Json.Default.decodeFromString(BaseItemDto.Companion.serializer(), folderJson);
        }
        mIncludeType = args != null ? args.getString(Extras.IncludeType, null) : null;
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        if (mFolder == null) {
            return new View(requireContext());
        }
        return LibraryGridScreenKt.createLibraryGridComposeView(
                requireContext(),
                mFolder,
                mIncludeType
        );
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return false;
    }
}
