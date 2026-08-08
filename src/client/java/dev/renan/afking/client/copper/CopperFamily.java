package dev.renan.afking.client.copper;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

public enum CopperFamily {

    COPPER_BLOCK(
            "Block of Copper",
            "Blocks of Copper",

            Items.COPPER_BLOCK.weathering().unaffected(),
            Blocks.COPPER_BLOCK.weathering().unaffected(),

            Items.COPPER_BLOCK.waxed().unaffected(),
            Blocks.COPPER_BLOCK.waxed().unaffected(),

            Items.COPPER_BLOCK.weathering().exposed(),
            Blocks.COPPER_BLOCK.weathering().exposed(),

            Items.COPPER_BLOCK.waxed().exposed(),
            Blocks.COPPER_BLOCK.waxed().exposed(),

            Items.COPPER_BLOCK.weathering().weathered(),
            Blocks.COPPER_BLOCK.weathering().weathered(),

            Items.COPPER_BLOCK.waxed().weathered(),
            Blocks.COPPER_BLOCK.waxed().weathered(),

            Items.COPPER_BLOCK.weathering().oxidized(),
            Blocks.COPPER_BLOCK.weathering().oxidized(),

            Items.COPPER_BLOCK.waxed().oxidized(),
            Blocks.COPPER_BLOCK.waxed().oxidized()
    ),

    CUT_COPPER(
            "Cut Copper",
            "Cut Copper",

            Items.CUT_COPPER.weathering().unaffected(),
            Blocks.CUT_COPPER.weathering().unaffected(),

            Items.CUT_COPPER.waxed().unaffected(),
            Blocks.CUT_COPPER.waxed().unaffected(),

            Items.CUT_COPPER.weathering().exposed(),
            Blocks.CUT_COPPER.weathering().exposed(),

            Items.CUT_COPPER.waxed().exposed(),
            Blocks.CUT_COPPER.waxed().exposed(),

            Items.CUT_COPPER.weathering().weathered(),
            Blocks.CUT_COPPER.weathering().weathered(),

            Items.CUT_COPPER.waxed().weathered(),
            Blocks.CUT_COPPER.waxed().weathered(),

            Items.CUT_COPPER.weathering().oxidized(),
            Blocks.CUT_COPPER.weathering().oxidized(),

            Items.CUT_COPPER.waxed().oxidized(),
            Blocks.CUT_COPPER.waxed().oxidized()
    ),

    COPPER_BULB(
            "Copper Bulb",
            "Copper Bulbs",

            Items.COPPER_BULB.weathering().unaffected(),
            Blocks.COPPER_BULB.weathering().unaffected(),

            Items.COPPER_BULB.waxed().unaffected(),
            Blocks.COPPER_BULB.waxed().unaffected(),

            Items.COPPER_BULB.weathering().exposed(),
            Blocks.COPPER_BULB.weathering().exposed(),

            Items.COPPER_BULB.waxed().exposed(),
            Blocks.COPPER_BULB.waxed().exposed(),

            Items.COPPER_BULB.weathering().weathered(),
            Blocks.COPPER_BULB.weathering().weathered(),

            Items.COPPER_BULB.waxed().weathered(),
            Blocks.COPPER_BULB.waxed().weathered(),

            Items.COPPER_BULB.weathering().oxidized(),
            Blocks.COPPER_BULB.weathering().oxidized(),

            Items.COPPER_BULB.waxed().oxidized(),
            Blocks.COPPER_BULB.waxed().oxidized()
    );

    private final String singularOutputName;
    private final String pluralOutputName;

    private final CopperVariant unaffected;
    private final CopperVariant waxedUnaffected;

    private final CopperVariant exposed;
    private final CopperVariant waxedExposed;

    private final CopperVariant weathered;
    private final CopperVariant waxedWeathered;

    private final CopperVariant oxidized;
    private final CopperVariant waxedOxidized;

    CopperFamily(
            String singularOutputName,
            String pluralOutputName,

            Item unaffectedItem,
            Block unaffectedBlock,

            Item waxedUnaffectedItem,
            Block waxedUnaffectedBlock,

            Item exposedItem,
            Block exposedBlock,

            Item waxedExposedItem,
            Block waxedExposedBlock,

            Item weatheredItem,
            Block weatheredBlock,

            Item waxedWeatheredItem,
            Block waxedWeatheredBlock,

            Item oxidizedItem,
            Block oxidizedBlock,

            Item waxedOxidizedItem,
            Block waxedOxidizedBlock
    ) {
        this.singularOutputName = singularOutputName;
        this.pluralOutputName = pluralOutputName;

        this.unaffected = new CopperVariant(
                this,
                CopperOxidationStage.UNAFFECTED,
                false,
                unaffectedItem,
                unaffectedBlock
        );

        this.waxedUnaffected = new CopperVariant(
                this,
                CopperOxidationStage.UNAFFECTED,
                true,
                waxedUnaffectedItem,
                waxedUnaffectedBlock
        );

        this.exposed = new CopperVariant(
                this,
                CopperOxidationStage.EXPOSED,
                false,
                exposedItem,
                exposedBlock
        );

        this.waxedExposed = new CopperVariant(
                this,
                CopperOxidationStage.EXPOSED,
                true,
                waxedExposedItem,
                waxedExposedBlock
        );

        this.weathered = new CopperVariant(
                this,
                CopperOxidationStage.WEATHERED,
                false,
                weatheredItem,
                weatheredBlock
        );

        this.waxedWeathered = new CopperVariant(
                this,
                CopperOxidationStage.WEATHERED,
                true,
                waxedWeatheredItem,
                waxedWeatheredBlock
        );

        this.oxidized = new CopperVariant(
                this,
                CopperOxidationStage.OXIDIZED,
                false,
                oxidizedItem,
                oxidizedBlock
        );

        this.waxedOxidized = new CopperVariant(
                this,
                CopperOxidationStage.OXIDIZED,
                true,
                waxedOxidizedItem,
                waxedOxidizedBlock
        );
    }

    public CopperVariant variant(
            CopperOxidationStage stage,
            boolean waxed
    ) {
        return switch (stage) {
            case UNAFFECTED ->
                    waxed
                            ? waxedUnaffected
                            : unaffected;

            case EXPOSED ->
                    waxed
                            ? waxedExposed
                            : exposed;

            case WEATHERED ->
                    waxed
                            ? waxedWeathered
                            : weathered;

            case OXIDIZED ->
                    waxed
                            ? waxedOxidized
                            : oxidized;
        };
    }

    public CopperVariant finalOutput() {
        return unaffected;
    }

    public List<CopperVariant> variants() {
        return List.of(
                unaffected,
                waxedUnaffected,
                exposed,
                waxedExposed,
                weathered,
                waxedWeathered,
                oxidized,
                waxedOxidized
        );
    }

    public String singularOutputName() {
        return singularOutputName;
    }

    public String pluralOutputName() {
        return pluralOutputName;
    }
}