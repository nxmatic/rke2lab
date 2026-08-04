package io.nxmatic.rke2lab.incus.edge;

import io.nxmatic.rke2lab.incus.contract.ImageBuildRequest;
import io.nxmatic.rke2lab.incus.contract.ImageBuilder;
import org.osgi.service.component.annotations.Component;

/**
 * The SURVEYING incus image-build edge — plans the build without touching anything. It shells no
 * {@code nix}, opens no {@code ssh}: {@link #build} returns normally without building (the honest
 * plan — the build WOULD run cleanly; a survey cannot claim the artifacts exist, and the step
 * renders PENDING, so this is a plan, never a fabricated success). The single honest thing a survey
 * CAN assert is the recipe {@link #recipeDigest() digest} — pure over the bundle resources, so it
 * is IDENTICAL to the cultivating builder's; the host's image-cache key must not move between the
 * modes.
 *
 * <p>One of the ImageBuilder PAIR: registered with {@code rke2lab.gardening=surveying} so the
 * frontier picks it when the ambient RunGate is surveying. Its twin, {@link
 * CultivatingNixosImageBuilder}, builds for real.
 */
@Component(service = ImageBuilder.class, property = "rke2lab.gardening=surveying")
public final class SurveyingImageBuilder implements ImageBuilder {

  private final BuildRecipe recipe = new BuildRecipe();

  @Override
  public void build(ImageBuildRequest request) {
    // Plans only — shells nothing, returns normally. The step renders PENDING (never a fabricated
    // success); the honest assertion a survey makes is the recipe digest, not the artifacts.
  }

  @Override
  public String recipeDigest() {
    return recipe.digest();
  }
}
