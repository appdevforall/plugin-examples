package dev.omar.plugin.iconsrepo.ui.fragments;

import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.megatronking.svg.generator.svg.SvgSAXReader;
import com.github.megatronking.svg.generator.svg.model.Svg;
import com.github.megatronking.svg.generator.writer.impl.Svg2VectorTemplateWriter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.itsaky.androidide.plugins.extensions.IModule;
import com.itsaky.androidide.plugins.extensions.IProject;
import com.itsaky.androidide.plugins.extensions.ModuleType;
import com.itsaky.androidide.plugins.extensions.ProjectType;
import com.itsaky.androidide.plugins.extensions.SourceSet;
import com.itsaky.androidide.plugins.services.IdeProjectService;

import dev.omar.plugin.iconsrepo.data.importer.AppPathResolver;
import dev.omar.plugin.iconsrepo.data.importer.AssetIconSource;
import dev.omar.plugin.iconsrepo.data.importer.ImportIconResult;
import dev.omar.plugin.iconsrepo.data.importer.ImportParams;
import dev.omar.plugin.iconsrepo.data.importer.SvgToVectorImporter;
import org.dom4j.DocumentException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.List;

import dev.omar.plugin.iconsrepo.Main;
import dev.omar.plugin.iconsrepo.data.validation.HexColorRule;
import dev.omar.plugin.iconsrepo.data.validation.IconNameRule;
import dev.omar.plugin.iconsrepo.data.validation.ValidationTextWatcher;
import dev.omar.plugin.iconsrepo.data.validation.Validator;
import dev.omar.plugin.iconsrepo.databinding.DialogImportIconBinding;
import dev.omar.plugin.iconsrepo.databinding.FragmentMainBinding;
import dev.omar.plugin.iconsrepo.models.IconModel;
import dev.omar.plugin.iconsrepo.repository.IconRepository;
import dev.omar.plugin.iconsrepo.ui.adapter.IconsAdapter;
import dev.omar.plugin.iconsrepo.utils.DynamicColorHelper;

public class MainFragment extends PluginFragment {

    private FragmentMainBinding binding;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentMainBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    @MainThread
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupRecyclerView();

        final IconsAdapter adapter = new IconsAdapter();
        adapter.setOnItemClickListener(
                (model, itemView, position) -> {
                    showImportIconDialog(model);
                });
        binding.searchEditText.addTextChangedListener(adapter);
        binding.iconsRecyclerView.setAdapter(adapter);

        binding.loadingProgressBar.setVisibility(View.VISIBLE);
        binding.iconsRecyclerView.setVisibility(View.GONE);

        binding.searchEditText.setEnabled(false);

        Main.getInstance()
                .getIconRepository()
                .loadAllIconsAsync(
                        new IconRepository.LoadIconsCallback() {

                            @Override
                            public void onLoadded(List<IconModel> list) {
                                binding.searchEditText.setEnabled(true);
                                binding.loadingProgressBar.setVisibility(View.GONE);
                                if (list != null && list.isEmpty()) {
                                    binding.emptyStateText.setVisibility(View.VISIBLE);
                                } else {
                                    binding.iconsRecyclerView.setVisibility(View.VISIBLE);
                                    adapter.setOriginalList(list);
                                    if (list != null) {
                                        binding.searchInputLayout.setHint(
                                                "Search in " + list.size() + " icon");
                                    }
                                }
                            }

                            @Override
                            public void onError(Throwable th) {
                                binding.loadingProgressBar.setVisibility(View.GONE);
                                binding.emptyStateText.setVisibility(View.VISIBLE);
                                binding.emptyStateText.setText("Error: " + th.getMessage());
                            }
                        });
    }

    private void setupRecyclerView() {
        final RecyclerView recyclerView = binding.iconsRecyclerView;
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 4));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);

        final RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        pool.setMaxRecycledViews(0, 30);
        recyclerView.setRecycledViewPool(pool);
    }

    private void showImportIconDialog(@NonNull final IconModel model) {

        final DialogImportIconBinding dialogBinding =
                DialogImportIconBinding.inflate(getLayoutInflater());
        setupInputName(dialogBinding, model);
        setupColorsList(dialogBinding);

        final IdeProjectService projectService = Main.getInstance().getProjectService();
        final IProject currentProject = projectService.getCurrentProject();

        final MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(requireContext());
        dialog.setTitle("Import vector icon");
        dialog.setView(dialogBinding.getRoot());
        dialog.setPositiveButton("Import", null);
        dialog.setNegativeButton("Cancel", null);
        final AlertDialog alertDialog = dialog.show();

        alertDialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            boolean isValidName =
                                    Validator.validate(
                                            dialogBinding.inputName, List.of(new IconNameRule()));
                            int selectedColorPosition =
                                    dialogBinding.txtListColors.getSelectedItemPosition();
                            boolean isValidColor = selectedColorPosition > 1;

                            if (selectedColorPosition == 0) {
                                isValidColor = true;
                            } else if (selectedColorPosition == 1) {
                                isValidColor =
                                        Validator.validate(
                                                dialogBinding.inputColor,
                                                List.of(new HexColorRule()));
                            }
                            if (isValidName && isValidColor) {

                                try {
                                    importVectorIcon(dialogBinding, currentProject, model);
                                    alertDialog.dismiss();
                                } catch (Exception err) {
                                    Toast.makeText(
                                                    getContext(),
                                                    "Error : " + err.getMessage(),
                                                    Toast.LENGTH_LONG)
                                            .show();
                                }
                            }
                        });
    }

    private void importVectorIcon(
            DialogImportIconBinding dialogBinding, IProject currentProject, IconModel model)
            throws IllegalArgumentException {

        ImportParams params =
                new ImportParams(currentProject, new AssetIconSource(dialogBinding.inputName.getText().toString(),model.getData()), new AppPathResolver());
                params.useTint(resolveColorValue(dialogBinding));
        ImportIconResult result = new SvgToVectorImporter().importIcon(params);
        if (!result.isSuccess()) {
            throw new IllegalArgumentException(result.getMessage());
        }
    }

    private String resolveColorValue(DialogImportIconBinding dialogBinding) {
        int selectedColorPosition = dialogBinding.txtListColors.getSelectedItemPosition();
        if(selectedColorPosition > 1){
            DynamicColorHelper.ColorReferenceModel model = DynamicColorHelper.getDynamicColors().get(selectedColorPosition - 2);
            return model.getRef();
        }else if(selectedColorPosition == 1){
            return dialogBinding.inputColor.getText().toString();
        }
        return null;
    }

    private void setupInputName(DialogImportIconBinding dialogBinding, IconModel model) {
        dialogBinding.inputName.setText("ic_" + model.getIconName().replaceAll("-", "_"));
        dialogBinding.inputName.addTextChangedListener(
                new ValidationTextWatcher(dialogBinding.inputName, List.of(new IconNameRule())));
    }

    private void setupColorsList(DialogImportIconBinding dialogBinding) {
        final MutableLiveData<Integer> colorPreview =
                new MutableLiveData<>(
                        DynamicColorHelper.resolveDynamicColor(
                                getContext(),
                                DynamicColorHelper.getColorModel(
                                        com.google.android.material.R.attr.colorOnSurface)));

        List<String> colorsNames = DynamicColorHelper.getDisplayNames();
        colorsNames.add(0, "None");
        colorsNames.add(1, "Custom");
        dialogBinding.txtListColors.setAdapter(
                new ArrayAdapter<>(
                        requireContext(), android.R.layout.simple_list_item_1, colorsNames));
        dialogBinding.txtListColors.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        dialogBinding.inputColorLayout.setVisibility(
                                (position == 1) ? View.VISIBLE : View.GONE);
                        try {
                            if (position == 0) {
                                colorPreview.postValue(Color.BLACK);
                            } else if (position == 1) {
                                colorPreview.postValue(
                                        Color.parseColor(
                                                String.valueOf(
                                                        dialogBinding.inputColor.getText())));
                            } else {
                                colorPreview.postValue(
                                        DynamicColorHelper.resolveDynamicColor(
                                                getContext(),
                                                DynamicColorHelper.getColorModel(
                                                        DynamicColorHelper.getDynamicColors()
                                                                .get(Math.max(0, position - 2))
                                                                .getAttrId())));
                            }
                        } catch (Exception e) {
                            colorPreview.postValue(Color.BLACK);
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });

        dialogBinding.inputColor.addTextChangedListener(
                new ValidationTextWatcher(
                        dialogBinding.inputColor,
                        List.of(new HexColorRule()),
                        (text, state) -> {
                            if (state) {
                                colorPreview.postValue(Color.parseColor(text));
                            }
                        }));

        colorPreview.observeForever(
                value -> {
                    dialogBinding.iconPreview.setColorFilter(value, PorterDuff.Mode.SRC_IN);
                });
    }
}
