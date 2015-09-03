package org.p57.model.collada;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.thoughtworks.xstream.XStream;
import org.apache.commons.beanutils.PropertyUtils;
import org.p57.model.ModelLoader;
import org.p57.model.collada.format.Collada;
import org.p57.model.collada.format.collada.LinkedCollada;
import org.p57.model.lwjgl.LWJGLModel;

import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Collada loader class.
 *
 * Created by Pavel on 05.06.2015.
 */
public class ColladaLoader implements ModelLoader<LWJGLModel> {

    private static final String ID_PROPERTY = "id";
    private static final String SEMANTIC_PROPERTY = "semantic";

    private static final Set<String> ALLOWED_VERSIONS = ImmutableSet.of(
            "1.4.1"
    );

    private final XStream xstream = new XStream();

    public ColladaLoader() {
        xstream.autodetectAnnotations(true);
        xstream.processAnnotations(Collada.class);
        xstream.ignoreUnknownElements();
    }

    @Override
    public LWJGLModel load(final String path) {
        final Collada collada = (Collada) xstream.fromXML(new File(path));

        final LinkedCollada linkedCollada = linkCollada(collada);

        //some preparation code here

        return null; // stub
    }

    private LinkedCollada linkCollada(final Collada collada) {
        if (!ALLOWED_VERSIONS.contains(collada.getVersion())) {
            throw new UnsupportedOperationException(
                    String.format("Collada version %s isn't supported", collada.getVersion())
            );
        }

        final Map<String, LinkedCollada.Image> libraryImages
                = map(collada.getLibraryImages(), LinkedCollada.Image::new);

        final Map<String, LinkedCollada.Effect> libraryEffects
                = linkEffects(collada.getLibraryEffects(), libraryImages);

        final Map<String, LinkedCollada.Material> libraryMaterials
                = linkMaterials(collada.getLibraryMaterials(), libraryEffects);

        final Map<String, LinkedCollada.Geometry> libraryGeometries
                = linkGeometries(collada.getLibraryGeometries(), libraryMaterials);

        final Map<String, LinkedCollada.VisualScene> libraryScenes
                = linkVisualScenes(collada.getLibraryVisualScenes(), libraryGeometries, libraryMaterials);

        return new LinkedCollada(
                collada,
                libraryImages,
                libraryMaterials,
                libraryEffects,
                libraryGeometries,
                libraryScenes,
                linkScenes(collada.getScenes(), libraryScenes)
        );
    }

    private Map<String, LinkedCollada.Material> linkMaterials(
            final List<Collada.Material> srcMaterials,
            final Map<String, LinkedCollada.Effect> libraryEffects) {
        return map(srcMaterials, (Collada.Material srcMaterial) -> {
            return new LinkedCollada.Material(
                    srcMaterial,
                    prepareEffects(srcMaterial.getInstanceEffects(), libraryEffects)
            );
        });
    }

    private List<LinkedCollada.Effect> prepareEffects(final List<Collada.InstanceEffect> srcEffects,
                                                      final Map<String, LinkedCollada.Effect> libraryEffects) {

        return Lists.transform(srcEffects, (Collada.InstanceEffect instanceEffect) -> {
            final LinkedCollada.Effect effect = libraryEffects.get(
                    idFromLink(instanceEffect.getUrl())
            );
            Objects.requireNonNull(effect, String.format("Effect not found by URL: %s", instanceEffect.getUrl()));
            return effect;
        });
    }

    private String idFromLink(final String urlString) {
        return URI.create(urlString).getFragment();
    }

    private Map<String, LinkedCollada.Effect> linkEffects(
            final List<Collada.Effect> libraryEffects,
            final Map<String, LinkedCollada.Image> libraryImages) {
        return map(libraryEffects, (Collada.Effect srcEffect) -> {
            return new LinkedCollada.Effect(
                    srcEffect,
                    linkCommonProfile(srcEffect.getCommonProfile(), libraryImages)
            );
        });
    }

    private LinkedCollada.Profile linkCommonProfile(
            final Collada.Profile srcProfile,
            final Map<String, LinkedCollada.Image> libraryImages) {
        return new LinkedCollada.Profile(
                srcProfile,
                linkTechiques(srcProfile.getTechiques(), libraryImages)
        );
    }

    private Map<String, LinkedCollada.Profile.Technique> linkTechiques(
            final List<Collada.Profile.Technique> srcTechniques,
            final Map<String, LinkedCollada.Image> libraryImages) {
        return map(srcTechniques, (Collada.Profile.Technique srcTechnique) -> {
            return new LinkedCollada.Profile.Technique(
                    srcTechnique,
                    linkPhong(srcTechnique.getPhong(), libraryImages));
        });

    }

    private LinkedCollada.Phong linkPhong(
            final Collada.Phong srcPhong,
            final Map<String, LinkedCollada.Image> libraryImages) {
        return new LinkedCollada.Phong(
                srcPhong,
                linkTextureParam(srcPhong.getEmission(), libraryImages),
                linkTextureParam(srcPhong.getDiffuse(), libraryImages)
        );
    }

    private LinkedCollada.TextureParam linkTextureParam(
            final Collada.TextureParam srcParam,
            final Map<String, LinkedCollada.Image> images) {
        final String imageId = srcParam.getTexture().getTexture();
        final LinkedCollada.Image image = images.get(imageId);
        Objects.requireNonNull(image, String.format("Image not found by link: %s", imageId));
        return new LinkedCollada.TextureParam(
                srcParam,
                image
        );
    }

    private Map<String, LinkedCollada.Geometry> linkGeometries(
            final List<Collada.Geometry> libGeometries,
            final Map<String, LinkedCollada.Material> materials) {

        return map(libGeometries, (final Collada.Geometry srcGeometry) -> {
            return new LinkedCollada.Geometry(
                    srcGeometry,
                    linkMesh(srcGeometry.getMesh(), srcGeometry, materials)
            );
        });
    }

    private LinkedCollada.Mesh linkMesh(
            final Collada.Mesh srcMesh,
            final Collada.Geometry geometry,
            final Map<String, LinkedCollada.Material> materials) {

        final Map<String, LinkedCollada.Source> sources
                = map(srcMesh.getSources(), LinkedCollada.Source::new);

        final Map<String, LinkedCollada.Vertices> vertices
                = linkVertices(srcMesh.getVertices(), sources);

        return new LinkedCollada.Mesh(
                srcMesh,
                sources,
                vertices,
                linkTriangles(srcMesh.getTriangles(), geometry, materials, sources, vertices)
        );
    }

    private Map<String, LinkedCollada.Vertices> linkVertices(
            final List<Collada.Vertices> srcVerticesCollection,
            final Map<String, LinkedCollada.Source> sources) {

        return map(srcVerticesCollection, (final Collada.Vertices srcVertices) -> {
            final Map<String, LinkedCollada.Source> semanticSources =
                    linkSemanticSources(srcVertices.getInputs(), sources);

            final LinkedCollada.Source position
                    = semanticSources.get(LinkedCollada.Semantic.POSITION.value());
            Objects.requireNonNull(position, "Input with semantic=\"POSITION\" must be set for <vertices> tag.");

            return new LinkedCollada.Vertices(
                    srcVertices,
                    semanticSources,
                    position
            );
        });
    }

    private Map<String, LinkedCollada.Source> linkSemanticSources(
            final List<Collada.Input> inputs,
            final Map<String, LinkedCollada.Source> sources) {
        final Map<String, LinkedCollada.Source> outMap = new HashMap<>();
        for (final Collada.Input src : inputs) {
            final String idRef = idFromLink(src.getSource());

            final LinkedCollada.Source source = sources.get(idRef);
            Objects.requireNonNull(source, String.format("Source not found by id: %s", idRef));

            outMap.put(src.getSemantic(), source);
        }

        return outMap;
    }

    private LinkedCollada.Triangles linkTriangles(
            final Collada.Triangles src,
            final Collada.Geometry geometry,
            final Map<String, LinkedCollada.Material> materials,
            final Map<String, LinkedCollada.Source> sources,
            final Map<String, LinkedCollada.Vertices> vertices) {

        final Map<String, Collada.Input> inputs = map(src.getInputs(), SEMANTIC_PROPERTY);

        final LinkedCollada.Source normal = prepareSource(sources, LinkedCollada.Semantic.NORMAL, inputs);
        Objects.requireNonNull(normal, String.format("Normals are absent for geometry %s", geometry.getName()));

        final LinkedCollada.Source texcoord = prepareSource(
                sources,
                LinkedCollada.Semantic.TEXTURE_COORDINATE,
                inputs);


        return new LinkedCollada.Triangles(
                src,
                materials.get(src.getMaterial()),
                inputs,
                prepareVertex(vertices, inputs),
                normal,
                texcoord
        );
    }

    private LinkedCollada.Vertices prepareVertex(
            final Map<String, LinkedCollada.Vertices> verticesMap,
            final Map<String, Collada.Input> inputs) {
        final Collada.Input vertexInput = inputs.get(LinkedCollada.Semantic.VERTEX.value());
        Objects.requireNonNull(vertexInput, "It's required to have at least one input with semantic=\"VERTEX\" inside " +
                "triangles");

        final String verticesId = idFromLink(vertexInput.getSource());
        final LinkedCollada.Vertices vertices = verticesMap.get(verticesId);
        Objects.requireNonNull(vertices, String.format("Vertices reference not found: %s", verticesId));
        return vertices;
    }

    private LinkedCollada.Source prepareSource(
            final Map<String, LinkedCollada.Source> sources,
            final LinkedCollada.Semantic semantic,
            final Map<String, Collada.Input> inputs) {
        final Collada.Input sourceInput = inputs.get(semantic.value());
        if (sourceInput == null) {
            return null;
        }

        final String sourceId = idFromLink(sourceInput.getSource());
        final LinkedCollada.Source source = sources.get(sourceId);
        Objects.requireNonNull(source, String.format("Source not found by id: %s", sourceId));
        return source;
    }

    private Map<String, LinkedCollada.VisualScene> linkVisualScenes(
            final List<Collada.VisualScene> libraryVisualScenes,
            final Map<String, LinkedCollada.Geometry> geometries,
            final Map<String, LinkedCollada.Material> materials) {
        return map(libraryVisualScenes, (final Collada.VisualScene src) -> {
            return new LinkedCollada.VisualScene(
                    src,
                    linkNodes(src.getNodes(), geometries, materials)
            );
        });
    }

    private List<LinkedCollada.Node> linkNodes(
            final List<Collada.Node> nodes,
            final Map<String, LinkedCollada.Geometry> geometries,
            final Map<String, LinkedCollada.Material> materials) {
        return Lists.transform(nodes, (final Collada.Node src) ->
            new LinkedCollada.Node(
                    src,
                    linkGeometry(src.getGeometry(), geometries, materials)
            )
        );
    }

    private LinkedCollada.NodeGeometry linkGeometry(
            final Collada.NodeGeometry src,
            final Map<String, LinkedCollada.Geometry> geometries,
            final Map<String, LinkedCollada.Material> materials) {
        final LinkedCollada.Geometry geometry = geometries.get(idFromLink(src.getUrl()));
        Objects.requireNonNull(geometry,
                String.format("Node geometry cannot be found by url: [%s]", src.getUrl()));

        return new LinkedCollada.NodeGeometry(
                src,
                linkBindMaterial(src.getMaterial(), materials),
                geometry);
    }

    private LinkedCollada.BindMaterial linkBindMaterial(
            final Collada.BindMaterial src,
            final Map<String, LinkedCollada.Material> materials) {
        return new LinkedCollada.BindMaterial(
                src,
                linkMaterialTechnique(src.getCommonTechnique(), materials));
    }

    private LinkedCollada.BindMaterial.Technique linkMaterialTechnique(
            final Collada.BindMaterial.Technique technique,
            final Map<String, LinkedCollada.Material> materials) {
        return new LinkedCollada.BindMaterial.Technique(
                technique,
                linkInstanceMaterial(technique.getInstanceMaterial(), materials)
        );
    }

    private LinkedCollada.InstanceMaterial linkInstanceMaterial(
            final Collada.InstanceMaterial src,
            final Map<String, LinkedCollada.Material> materials) {
        final String materialId = idFromLink(src.getTarget());
        final LinkedCollada.Material material = materials.get(materialId);
        Objects.requireNonNull(material,
                String.format("Material to bind cannot be found by id: [%s]", materialId));

        return new LinkedCollada.InstanceMaterial(src, material);
    }

    private List<LinkedCollada.Scene> linkScenes(
            final List<Collada.Scene> scenes,
            final Map<String, LinkedCollada.VisualScene> visualScenes) {
        return Lists.transform(scenes, (final Collada.Scene src) ->
            new LinkedCollada.Scene(
                    src,
                    linkSceneInstances(src.getInstanceScenes(), visualScenes)
            )
        );
    }

    private List<LinkedCollada.InstanceVisualScene> linkSceneInstances(
            final List<Collada.InstanceVisualScene> instanceScenes,
            final Map<String, LinkedCollada.VisualScene> scenes) {

        return Lists.transform(instanceScenes, (final Collada.InstanceVisualScene src) -> {
            final String sceneId = idFromLink(src.getUrl());
            final LinkedCollada.VisualScene visualScene = scenes.get(sceneId);
            Objects.requireNonNull(visualScene,
                    String.format("Couldn't find instance visual scene by id [%s]", sceneId)
            );

            return new LinkedCollada.InstanceVisualScene(
                    src,
                    visualScene
            );
        });
    }


    private static <TSrc> Map<String, TSrc> map(
            final Iterable<TSrc> src) {
        return map(src, ID_PROPERTY);
    }

    private static <TSrc> Map<String, TSrc> map(
            final Iterable<TSrc> src,
            final String idProperty) {
        return map(src, idProperty, (TSrc item) -> item);
    }


    private static <TSrc, TDest> Map<String, TDest> map(
            final Iterable<TSrc> src,
            final Function<TSrc, TDest> converter) {
        return map(src, ID_PROPERTY, converter);
    }

    private static <TSrc, TDest> Map<String, TDest> map(
            final Iterable<TSrc> src,
            final String idProperty,
            final Function<TSrc, TDest> converter) {
        try {
            final Map<String, TDest> prepared = new HashMap<>();
            for (final TSrc srcItem : src) {
                final String idValue = (String) PropertyUtils.getProperty(srcItem, idProperty);
                prepared.put(idValue, converter.apply(srcItem));
            }
            return prepared;
        }
        catch (final Exception e) {
            throw Throwables.propagate(e);
        }
    }
}
