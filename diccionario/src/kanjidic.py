"""Parser de KANJIDIC2 (kanjidic2.xml, descomprimido)."""
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field


@dataclass
class Kanji:
    kanji: str
    significados: list = field(default_factory=list)
    on_yomi: list = field(default_factory=list)
    kun_yomi: list = field(default_factory=list)
    jlpt: int = None
    strokes: int = None


def parsear_kanjidic(ruta: str) -> list:
    """Parsear archivo KANJIDIC2 XML y retornar lista de Kanji.

    Args:
        ruta: Ruta al archivo kanjidic2.xml

    Returns:
        Lista de objetos Kanji con literales, significados, lecturas y metadatos.
    """
    kanjis = []
    # iterparse: el archivo real pesa ~15 MB, evita cargar el árbol entero
    for _, elem in ET.iterparse(ruta, events=('end',)):
        if elem.tag != 'character':
            continue
        misc = elem.find('misc')
        jlpt = misc.findtext('jlpt') if misc is not None else None
        strokes = misc.findtext('stroke_count') if misc is not None else None
        on_yomi, kun_yomi, significados = [], [], []
        for r in elem.iter('reading'):
            if r.get('r_type') == 'ja_on' and r.text:
                on_yomi.append(r.text)
            elif r.get('r_type') == 'ja_kun' and r.text:
                kun_yomi.append(r.text)
        for m in elem.iter('meaning'):
            if m.get('m_lang') is None and m.text:
                significados.append(m.text)
        kanjis.append(Kanji(
            kanji=elem.findtext('literal'),
            significados=significados,
            on_yomi=on_yomi,
            kun_yomi=kun_yomi,
            jlpt=int(jlpt) if jlpt else None,
            strokes=int(strokes) if strokes else None,
        ))
        elem.clear()
    return kanjis
