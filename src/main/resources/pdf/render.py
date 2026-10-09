"""Run inside Scribus: replace the nine template images and export each sheet."""
import json
import os
import sys
import traceback
from pathlib import Path
from xml.etree import ElementTree as ET


def prepare_sheet(template, images, destination):
    tree = ET.parse(template)
    document = tree.getroot().find('DOCUMENT')
    frames = [item for item in document.findall('PAGEOBJECT') if item.get('PTYPE') == '2']
    if len(frames) != 9 or len(document.findall('PAGE')) != 1:
        raise ValueError('Expected a one-page template with nine image frames.')
    # Keep the original, slightly asymmetric printer-calibrated coordinates.
    frames.sort(key=lambda frame: (float(frame.get('YPOS')), float(frame.get('XPOS'))))
    for index, frame in enumerate(frames):
        if index >= len(images):
            document.remove(frame)
            continue
        frame.set('ANNAME', 'card-slot-' + str(index))
        frame.set('PFILE', str(Path(images[index]).resolve()))
        frame.set('LOCALX', '0')
        frame.set('LOCALY', '0')
    document.find('PDF').set('openAfterExport', '0')
    tree.write(destination, encoding='utf-8', xml_declaration=True)
    return len(images)


def render(manifest_file):
    import scribus
    manifest = json.loads(Path(manifest_file).read_text(encoding='utf-8'))
    output = Path(manifest['output'])
    images = manifest['images']
    for start in range(0, len(images), 9):
        sheet = start // 9 + 1
        sla = output / ('sheet-%03d.sla' % sheet)
        pdf_path = output / ('sheet-%03d.pdf' % sheet)
        count = prepare_sheet(manifest['template'], images[start:start + 9], sla)
        if not scribus.openDoc(str(sla)):
            raise RuntimeError('Scribus could not open the prepared template.')
        scribus.setUnit(scribus.UNIT_POINTS)
        scribus.setRedraw(False)
        for index in range(count):
            # The original frames use non-proportional automatic fitting (RATIO=0).
            scribus.setScaleImageToFrame(1, 0, 'card-slot-' + str(index))
        # PDFfile reads export settings from the opened document, including bleed.
        pdf = scribus.PDFfile()
        pdf.file = str(pdf_path)
        pdf.pages = [1]
        pdf.save()
        if not pdf_path.is_file() or pdf_path.stat().st_size == 0:
            raise RuntimeError('Scribus did not produce a PDF.')
        scribus.docChanged(False)
        scribus.closeDoc()
        sla.unlink()
    (output / 'render-success.json').write_text(json.dumps({'files': (len(images) + 8) // 9}), encoding='utf-8')


if __name__ == '__main__':
    try:
        render(sys.argv[1])
    except BaseException:
        traceback.print_exc()
        sys.stdout.flush()
        sys.stderr.flush()
        os._exit(1)
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(0)
