package net.earthcomputer.sssearch;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.searchtree.SearchRegistry;
import net.minecraft.client.searchtree.SearchTree;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Predicate;

public class SSSearchScreen extends EffectRenderingInventoryScreen<SSSearchScreen.ItemPickerMenu> {
    private static final ResourceLocation CREATIVE_TABS_LOCATION = new ResourceLocation("textures/gui/container/creative_inventory/tabs.png");
    private static final ResourceLocation SEARCH_TAB_LOCATION = new ResourceLocation("textures/gui/container/creative_inventory/tab_item_search.png");

    private final Screen parent;
    private EditBox searchBox;
    private final Set<TagKey<Item>> visibleTags = new HashSet<>();
    private float scrollOffs = 0;
    private boolean scrolling;
    private boolean justClicked;
    private final boolean displayOperatorCreativeTab;

    public SSSearchScreen(Screen parent, FeatureFlagSet featureFlagSet, Player player, boolean displayOperatorCreativeTab) {
        super(new ItemPickerMenu(player), player.getInventory(), CommonComponents.EMPTY);
        this.parent = parent;
        this.displayOperatorCreativeTab = displayOperatorCreativeTab;
        this.imageHeight = 136;
        this.imageWidth = 195;
        CreativeModeTabs.tryRebuildTabContents(featureFlagSet, hasPermissions(player), player.level().registryAccess());
    }

    private boolean hasPermissions(Player player) {
        return player.canUseGameMasterBlocks() && displayOperatorCreativeTab;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (searchBox != null) {
            searchBox.tick();
        }
    }

    @Override
    protected void slotClicked(Slot slot, int i, int j, ClickType clickType) {
        if (justClicked && slot != null && slot.hasItem()) {
            searchBox.moveCursorToEnd();
            searchBox.setHighlightPos(0);
            Item item = slot.getItem().getItem();
            Collection<GlobalPos> positions = SSSearchData.forCurrentWorld().getItemPositions(item);
            SSSearch.setPositionsToHighlight(positions);
            Component message;
            if (positions.isEmpty()) {
                message = Component.translatable("sssearch.render.noPositions", slot.getItem().getDisplayName()).withStyle(ChatFormatting.DARK_RED);
            } else {
                message = Component.translatable("sssearch.render.success", slot.getItem().getDisplayName());
            }
            Minecraft.getInstance().gui.getChat().addMessage(message);
        }
    }

    @Override
    protected void init() {
        super.init();
        searchBox = new EditBox(font, leftPos + 82, topPos + 6, 80, font.lineHeight, Component.translatable("itemGroup.search"));
        searchBox.setMaxLength(50);
        searchBox.setBordered(false);
        searchBox.setCanLoseFocus(false);
        searchBox.setFocused(true);
        searchBox.setTextColor(0xFFFFFF);
        addWidget(searchBox);
        refreshSearchResults();
        menu.scrollTo(0);

        addRenderableWidget(Button.builder(Component.translatable("sssearch.clear").withStyle(ChatFormatting.DARK_RED), button -> {
            SSSearchData searchData = SSSearchData.forCurrentWorld();
            searchData.clear();
            searchData.save();
            minecraft.gui.getChat().addMessage(Component.translatable("sssearch.clear.success"));
        }).bounds(2, 2, 100, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("sssearch.setupMode", SSSearch.setupMode ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF), button -> {
            SSSearch.setupMode = !SSSearch.setupMode;
            button.setMessage(Component.translatable("sssearch.setupMode", SSSearch.setupMode ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF));
        }).bounds(120, 2, 100, 20).build());
    }

    @Override
    public void resize(Minecraft minecraft, int i, int j) {
        String searchText = this.searchBox.getValue();
        init(minecraft, i, j);
        searchBox.setValue(searchText);
        if (!searchBox.getValue().isEmpty()) {
            refreshSearchResults();
        }
    }

    @Override
    public void onClose() {
        minecraft.player.closeContainer();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean charTyped(char c, int i) {
        String prevSearchText = searchBox.getValue();
        if (searchBox.charTyped(c, i)) {
            if (!Objects.equals(prevSearchText, searchBox.getValue())) {
                refreshSearchResults();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int i, int j, int k) {
        String prevSearchText = searchBox.getValue();
        if (searchBox.keyPressed(i, j, k)) {
            if (!Objects.equals(prevSearchText, searchBox.getValue())) {
                refreshSearchResults();
            }
            return true;
        }
        return i != GLFW.GLFW_KEY_ESCAPE || super.keyPressed(i, j, k);
    }

    private void refreshSearchResults() {
        menu.items.clear();
        visibleTags.clear();
        String searchText = searchBox.getValue();
        if (searchText.isEmpty()) {
            menu.items.addAll(BuiltInRegistries.CREATIVE_MODE_TAB.getOrThrow(CreativeModeTabs.SEARCH).getDisplayItems());
        } else {
            SearchTree<ItemStack> searchTree;
            if (searchText.startsWith("#")) {
                searchText = searchText.substring(1);
                searchTree = minecraft.getSearchTree(SearchRegistry.CREATIVE_TAGS);
                updateVisibleTags(searchText);
            } else {
                searchTree = minecraft.getSearchTree(SearchRegistry.CREATIVE_NAMES);
            }
            menu.items.addAll(searchTree.search(searchText.toLowerCase(Locale.ROOT)));
        }
        scrollOffs = 0;
        menu.scrollTo(0);
    }

    private void updateVisibleTags(String searchText) {
        Predicate<ResourceLocation> locationPredicate;
        int colonIndex = searchText.indexOf(':');
        if (colonIndex == -1) {
            locationPredicate = location -> location.getPath().contains(searchText);
        } else {
            String namespaceSearch = searchText.substring(0, colonIndex).trim();
            String pathSearch = searchText.substring(colonIndex + 1).trim();
            locationPredicate = location -> location.getNamespace().contains(namespaceSearch) && location.getPath().contains(pathSearch);
        }
        BuiltInRegistries.ITEM.getTagNames().filter(key -> locationPredicate.test(key.location())).forEach(visibleTags::add);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int i, int j) {
        RenderSystem.disableBlend();
        guiGraphics.drawString(font, Component.translatable("sssearch.name"), 8, 6, 0x404040, false);
    }

    @Override
    public boolean mouseClicked(double d, double e, int i) {
        if (i == GLFW.GLFW_MOUSE_BUTTON_1) {
            justClicked = true;
            if (insideScrollbar(d, e)) {
                scrolling = menu.canScroll();
                return true;
            }
        }
        boolean result = super.mouseClicked(d, e, i);
        justClicked = false;
        return result;
    }

    @Override
    public boolean mouseReleased(double d, double e, int i) {
        if (i == GLFW.GLFW_MOUSE_BUTTON_1) {
            scrolling = false;
        }
        return super.mouseReleased(d, e, i);
    }

    @Override
    public boolean mouseScrolled(double d, double e, double f) {
        if (!menu.canScroll()) {
            return false;
        }
        int scrollRate = (menu.items.size() + 9 - 1) / 9 - 5;
        float scrollDelta = (float)(f / (double)scrollRate);
        scrollOffs = Mth.clamp(scrollOffs - scrollDelta, 0, 1);
        menu.scrollTo(scrollOffs);
        return true;
    }
    
    private boolean insideScrollbar(double x, double y) {
        int left = leftPos + 175;
        int top = topPos + 18;
        int right = left + 14;
        int bottom = top + 112;
        return x >= left && y >= top && x < right && y < bottom;
    }

    @Override
    public boolean mouseDragged(double d, double e, int i, double f, double g) {
        if (scrolling) {
            int top = topPos + 18;
            int bottom = top + 112;
            scrollOffs = ((float)e - top - 7.5f) / ((float)(bottom - top) - 15f);
            scrollOffs = Mth.clamp(scrollOffs, 0, 1);
            menu.scrollTo(scrollOffs);
            return true;
        }
        return super.mouseDragged(d, e, i, f, g);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int i, int j, float f) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, i, j, f);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        renderTooltip(guiGraphics, i, j);
    }

    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack itemStack) {
        boolean isHovered = hoveredSlot != null;
        TooltipFlag.Default defaultFlag = minecraft.options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL;
        TooltipFlag tooltipFlag = isHovered ? defaultFlag.asCreative() : defaultFlag;
        List<Component> defaultTooltipLines = itemStack.getTooltipLines(minecraft.player, tooltipFlag);
        List<Component> tooltipLines = new ArrayList<>(defaultTooltipLines);
        if (isHovered) {
            visibleTags.forEach(tagKey -> {
                if (itemStack.is(tagKey)) {
                    tooltipLines.add(1, Component.literal("#" + tagKey.location()).withStyle(ChatFormatting.DARK_PURPLE));
                }
            });
        }

        return tooltipLines;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float f, int i, int j) {
        guiGraphics.blit(SEARCH_TAB_LOCATION, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        searchBox.render(guiGraphics, i, j, f);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        int scrollerLeft = leftPos + 175;
        int scrollerTop = topPos + 18;
        int scrollerBottom = scrollerTop + 112;
        guiGraphics.blit(CREATIVE_TABS_LOCATION, scrollerLeft, scrollerTop + (int)((float)(scrollerBottom - scrollerTop - 17) * scrollOffs), 232 + (menu.canScroll() ? 0 : 12), 0, 12, 15);
    }

    public static class ItemPickerMenu extends CreativeModeInventoryScreen.ItemPickerMenu {
        public ItemPickerMenu(Player player) {
            super(player);
            // remove player inv slots
            slots.subList(slots.size() - 9, slots.size()).clear();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int i) {
            return ItemStack.EMPTY;
        }
    }
}
