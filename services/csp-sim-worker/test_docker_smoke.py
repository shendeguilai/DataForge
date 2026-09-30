"""Linux sandbox acceptance. CI sets CSP_SIM_DOCKER_TESTS=1 after building the image."""
import base64
import os
import unittest
from worker import DockerSandbox


@unittest.skipUnless(os.environ.get("CSP_SIM_DOCKER_TESTS") == "1", "requires Docker and the built sandbox image")
class DockerSmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sandbox = DockerSandbox(os.environ.get("CSP_SIM_SANDBOX_IMAGE", "dataforge-csp-sandbox:local"))

    def compile(self, code):
        result = self.sandbox.run({"action": "compile", "source": base64.b64encode(code.encode()).decode()})
        self.assertEqual(result["verdict"], "AC", result)
        self.assertIn("g++ 9.3.0", result["environment"])
        return result["program"]

    def run_code(self, code, io="STDIO", memory=64, time_ms=1000):
        return self.sandbox.run({"action": "run", "program": self.compile(code), "input": base64.b64encode(b"1 2\n").decode(),
                                "ioMode": io, "inputName": "sum.in", "outputName": "sum.out", "memoryMb": memory, "timeLimitMs": time_ms})

    def test_stdio_and_file_io(self):
        code = '#include<bits/stdc++.h>\nusing namespace std;int main(){int a,b;cin>>a>>b;cout<<a+b<<"\\n";}'
        result = self.run_code(code)
        self.assertEqual(base64.b64decode(result["output"]), b"3\n")
        result = self.run_code(code.replace("int a,b;", 'freopen("sum.in","r",stdin);freopen("sum.out","w",stdout);int a,b;'), "FILE")
        self.assertEqual(base64.b64decode(result["output"]), b"3\n")

    def test_compile_error(self):
        result = self.sandbox.run({"action": "compile", "source": base64.b64encode(b"invalid c++").decode()})
        self.assertEqual(result["verdict"], "CE")

    def test_time_memory_runtime_and_output_limits(self):
        examples = [("int main(){while(true){}}", "TLE"),
                    ('#include<bits/stdc++.h>\nint main(){std::vector<char>x(512*1024*1024);}', "MLE"),
                    ('#include<cstdlib>\nint main(){abort();}', "RE"),
                    ('#include<cstdio>\nint main(){for(int i=0;i<20000000;i++)putchar(65);}', "OLE")]
        for code, verdict in examples:
            self.assertEqual(self.run_code(code, memory=32, time_ms=1000)["verdict"], verdict)

    def test_no_host_docker_socket_and_no_child_processes(self):
        code = '#include<bits/stdc++.h>\n#include<unistd.h>\nusing namespace std;int main(){ifstream f("/var/run/docker.sock");cout<<(!f)<<" "<<(fork()==-1);}'
        result = self.run_code(code)
        self.assertEqual(base64.b64decode(result["output"]), b"1 1")


if __name__ == "__main__":
    unittest.main()
