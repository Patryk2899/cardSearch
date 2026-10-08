import importlib.util
import sys
from pathlib import Path
import tempfile
import unittest
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
sys.dont_write_bytecode = True
TEMPLATE = ROOT / 'src/main/resources/pdf/nine-cards.sla'
spec = importlib.util.spec_from_file_location('pdf_render', TEMPLATE.with_name('render.py'))
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)


class TemplateTests(unittest.TestCase):
    def test_preserves_print_geometry_and_pdf_settings(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'sheet.sla'
            images = [str(Path(temporary) / ('card-%d.png' % index)) for index in range(9)]
            self.assertEqual(9, renderer.prepare_sheet(TEMPLATE, images, output))
            original = ET.parse(TEMPLATE).getroot().find('DOCUMENT')
            actual = ET.parse(output).getroot().find('DOCUMENT')
            self.assertEqual(original.attrib, actual.attrib)
            self.assertEqual(original.find('PAGE').attrib, actual.find('PAGE').attrib)
            expected_pdf = dict(original.find('PDF').attrib, openAfterExport='0')
            self.assertEqual(expected_pdf, actual.find('PDF').attrib)
            geometry = ('XPOS', 'YPOS', 'WIDTH', 'HEIGHT', 'OwnPage', 'RATIO', 'SCALETYPE', 'path', 'copath')
            for old, new in zip(original.findall('PAGEOBJECT'), actual.findall('PAGEOBJECT')):
                self.assertEqual({key: old.get(key) for key in geometry}, {key: new.get(key) for key in geometry})
            frames = sorted(actual.findall('PAGEOBJECT'), key=lambda item: (float(item.get('YPOS')), float(item.get('XPOS'))))
            self.assertEqual(images, [frame.get('PFILE') for frame in frames])

    def test_last_sheet_has_no_old_template_images(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'last-sheet.sla'
            renderer.prepare_sheet(TEMPLATE, [str(Path(temporary) / 'last.png')], output)
            frames = ET.parse(output).getroot().find('DOCUMENT').findall('PAGEOBJECT')
            self.assertEqual(1, len(frames))
            self.assertEqual('card-slot-0', frames[0].get('ANNAME'))
            self.assertEqual('82', frames[0].get('XPOS'))
            self.assertEqual('9', frames[0].get('YPOS'))


if __name__ == '__main__':
    unittest.main()
